"""Unit coverage for the pieces the adapters are built from."""

from __future__ import annotations

import pytest
from pydantic import SecretStr, ValidationError

from opspilot_agent.config import AiMode, Settings
from opspilot_agent.prompts import PromptName, load_prompt
from opspilot_agent.providers.errors import AiErrorCode, AiProviderError
from opspilot_agent.providers.factory import build_embedding_provider, build_llm_provider
from opspilot_agent.providers.http import HttpEmbeddingProvider, HttpLLMProvider
from opspilot_agent.providers.mock import MockEmbeddingProvider, MockLLMProvider
from opspilot_agent.providers.redaction import redact
from opspilot_agent.providers.resilience import CircuitBreaker, RetryPolicy
from opspilot_agent.providers.schemas import Classification, ReplyDraft
from opspilot_agent.providers.tokens import enforce_input_budget, estimate_tokens


class TestSchemas:
    def test_rejects_a_confidence_outside_the_documented_range(self) -> None:
        with pytest.raises(ValidationError):
            Classification(category="BILLING", urgency="LOW", summary="s", confidence=1.5)

    def test_rejects_an_over_long_summary(self) -> None:
        with pytest.raises(ValidationError):
            Classification(category="BILLING", urgency="LOW", summary="x" * 501, confidence=0.5)

    def test_rejects_an_unknown_category(self) -> None:
        with pytest.raises(ValidationError):
            Classification(category="ESCALATION", urgency="LOW", summary="s", confidence=0.5)

    def test_rejects_an_over_long_reply_body(self) -> None:
        with pytest.raises(ValidationError):
            ReplyDraft(body="x" * 4001)

    def test_rejects_unexpected_fields_a_model_might_invent(self) -> None:
        with pytest.raises(ValidationError):
            Classification(
                category="BILLING",
                urgency="LOW",
                summary="s",
                confidence=0.5,
                recommended_action="issue refund",
            )


class TestRedaction:
    def test_scrubs_a_known_literal_secret(self) -> None:
        assert "hunter2" not in redact("key is hunter2", "hunter2")

    def test_accepts_a_secret_str_without_the_caller_unwrapping_it(self) -> None:
        assert "hunter2" not in redact("key is hunter2", SecretStr("hunter2"))

    def test_scrubs_an_openai_style_key_it_was_never_told_about(self) -> None:
        assert "sk-abcdefgh12345678" not in redact("failed with sk-abcdefgh12345678")

    def test_scrubs_a_bearer_token_including_the_token_itself(self) -> None:
        scrubbed = redact("Authorization: Bearer abc123xyz")

        assert "abc123xyz" not in scrubbed

    def test_leaves_ordinary_text_alone(self) -> None:
        assert redact("the model id is wrong") == "the model id is wrong"

    def test_ignores_a_none_secret(self) -> None:
        assert redact("nothing to hide", None) == "nothing to hide"


class TestTokenBudget:
    def test_estimates_zero_for_empty_text(self) -> None:
        assert estimate_tokens("") == 0

    def test_estimate_grows_with_length(self) -> None:
        assert estimate_tokens("x" * 400) > estimate_tokens("x" * 40)

    def test_allows_a_request_inside_the_budget(self) -> None:
        estimated = enforce_input_budget([{"role": "user", "content": "short"}], 1000)

        assert estimated > 0

    def test_rejects_a_request_over_the_budget(self) -> None:
        with pytest.raises(AiProviderError) as caught:
            enforce_input_budget([{"role": "user", "content": "x" * 10_000}], 100)

        assert caught.value.code is AiErrorCode.INPUT_TOO_LARGE
        # The message must not imply a precision this heuristic does not have.
        assert "estimated" in str(caught.value)


class TestRetryPolicy:
    async def test_a_single_attempt_means_no_retry(self) -> None:
        calls = 0

        async def failing() -> None:
            nonlocal calls
            calls += 1
            raise AiProviderError(AiErrorCode.PROVIDER_ERROR, "nope", retryable=True)

        with pytest.raises(AiProviderError):
            await RetryPolicy(max_attempts=1, sleep=_no_sleep).run(failing)

        assert calls == 1

    async def test_does_not_retry_a_non_retryable_failure(self) -> None:
        calls = 0

        async def failing() -> None:
            nonlocal calls
            calls += 1
            raise AiProviderError(AiErrorCode.INPUT_TOO_LARGE, "too big")

        with pytest.raises(AiProviderError):
            await RetryPolicy(max_attempts=5, sleep=_no_sleep).run(failing)

        assert calls == 1

    async def test_backs_off_for_longer_on_each_successive_attempt(self) -> None:
        waits: list[float] = []

        async def record(seconds: float) -> None:
            waits.append(seconds)

        async def failing() -> None:
            raise AiProviderError(AiErrorCode.PROVIDER_ERROR, "nope", retryable=True)

        with pytest.raises(AiProviderError):
            await RetryPolicy(max_attempts=4, backoff_base_s=0.1, sleep=record).run(failing)

        assert waits == pytest.approx([0.1, 0.2, 0.4])

    def test_rejects_a_nonsense_attempt_budget(self) -> None:
        with pytest.raises(ValueError, match="at least 1"):
            RetryPolicy(max_attempts=0)


class TestCircuitBreaker:
    def test_opens_only_after_the_threshold_is_reached(self) -> None:
        breaker = CircuitBreaker(threshold=3, reset_after_s=60)

        breaker.record_failure()
        breaker.record_failure()
        assert not breaker.is_open

        breaker.record_failure()
        assert breaker.is_open

    def test_counts_consecutive_failures_only(self) -> None:
        breaker = CircuitBreaker(threshold=2, reset_after_s=60)

        breaker.record_failure()
        breaker.record_success()
        breaker.record_failure()

        assert not breaker.is_open

    def test_closes_again_once_the_reset_window_elapses(self) -> None:
        clock = [0.0]
        breaker = CircuitBreaker(threshold=1, reset_after_s=30, now=lambda: clock[0])

        breaker.record_failure()
        assert breaker.is_open

        clock[0] = 31.0
        assert not breaker.is_open

    def test_raise_if_open_reports_the_circuit_open_code(self) -> None:
        breaker = CircuitBreaker(threshold=1, reset_after_s=60)
        breaker.record_failure()

        with pytest.raises(AiProviderError) as caught:
            breaker.raise_if_open()

        assert caught.value.code is AiErrorCode.CIRCUIT_OPEN

    def test_rejects_a_nonsense_threshold(self) -> None:
        with pytest.raises(ValueError, match="at least 1"):
            CircuitBreaker(threshold=0, reset_after_s=1)


class TestPrompts:
    @pytest.mark.parametrize("name", list(PromptName))
    def test_every_named_prompt_revision_exists_on_disk(self, name: PromptName) -> None:
        assert load_prompt(name).strip()

    def test_prompt_names_carry_a_revision(self) -> None:
        # A prompt change must land as a new file, not an edit that silently rewrites history.
        assert all(".v" in name.value for name in PromptName)

    def test_prompts_tell_the_model_to_treat_ticket_text_as_data(self) -> None:
        # docs/API_CONTRACT.md: "prompt injection content is data, never system instruction".
        for name in PromptName:
            assert "untrusted" in load_prompt(name).lower()


class TestFactory:
    def test_mock_mode_builds_mock_providers(self) -> None:
        settings = Settings(ai_mode=AiMode.MOCK)

        assert isinstance(build_llm_provider(settings), MockLLMProvider)
        assert isinstance(build_embedding_provider(settings), MockEmbeddingProvider)

    def test_mock_embeddings_use_the_configured_width(self) -> None:
        provider = build_embedding_provider(Settings(ai_mode=AiMode.MOCK, embedding_dimensions=256))

        assert provider.dimensions == 256

    def test_real_mode_builds_http_providers(self) -> None:
        settings = Settings(
            ai_mode=AiMode.REAL,
            llm_api_key=SecretStr("sk-test-only"),
            llm_model_id="a-model",
            embedding_model_id="an-embed-model",
        )

        assert isinstance(build_llm_provider(settings), HttpLLMProvider)
        assert isinstance(build_embedding_provider(settings), HttpEmbeddingProvider)

    def test_real_mode_without_a_model_id_fails_with_a_clear_code(self) -> None:
        settings = Settings(ai_mode=AiMode.REAL, llm_api_key=SecretStr("sk-test-only"))

        with pytest.raises(AiProviderError) as caught:
            build_llm_provider(settings)

        assert caught.value.code is AiErrorCode.NOT_CONFIGURED
        assert "LLM_MODEL_ID" in str(caught.value)

    def test_real_mode_without_an_embedding_model_id_fails_with_a_clear_code(self) -> None:
        settings = Settings(ai_mode=AiMode.REAL, llm_api_key=SecretStr("sk-test-only"))

        with pytest.raises(AiProviderError) as caught:
            build_embedding_provider(settings)

        assert "EMBEDDING_MODEL_ID" in str(caught.value)


async def _no_sleep(_seconds: float) -> None:
    pass
