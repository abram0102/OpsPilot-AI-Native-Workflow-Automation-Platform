"""Real-adapter behaviour, driven entirely through `httpx.MockTransport`.

No network, no API key, no paid call — but every failure path the adapter claims
to handle is exercised against a real `httpx` client here, rather than asserted
in a docstring.
"""

from __future__ import annotations

import json

import httpx
import pytest
from pydantic import SecretStr

from opspilot_agent.providers.errors import AiErrorCode, AiProviderError
from opspilot_agent.providers.http import HttpEmbeddingProvider, HttpLLMProvider
from opspilot_agent.providers.resilience import CircuitBreaker, RetryPolicy
from opspilot_agent.providers.schemas import Classification, TicketCategory

# docs/TASKS.md T28: part of the security regression suite (`make test-security`) —
# provider error bodies echoing credentials, malformed model output.
pytestmark = pytest.mark.security


API_KEY = SecretStr("sk-test-only-not-a-real-key")

VALID_CLASSIFICATION = {
    "category": "BILLING",
    "urgency": "HIGH",
    "summary": "Customer was charged twice.",
    "confidence": 0.9,
}


def chat_response(content: object, status_code: int = 200) -> httpx.Response:
    text = content if isinstance(content, str) else json.dumps(content)
    return httpx.Response(status_code, json={"choices": [{"message": {"content": text}}]})


async def _no_sleep(_seconds: float) -> None:
    """Exercises the real backoff decisions without spending the wall-clock time."""


def build_llm(
    handler, *, max_attempts: int = 1, threshold: int = 99, max_input_tokens: int = 8000
) -> HttpLLMProvider:
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="https://provider")
    return HttpLLMProvider(
        client=client,
        model_id="test-model",
        api_key=API_KEY,
        retry=RetryPolicy(max_attempts=max_attempts, sleep=_no_sleep),
        breaker=CircuitBreaker(threshold=threshold, reset_after_s=60),
        max_input_tokens=max_input_tokens,
    )


def build_embedder(handler, *, dimensions: int = 1536) -> HttpEmbeddingProvider:
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="https://provider")
    return HttpEmbeddingProvider(
        client=client,
        model_id="test-embed-model",
        api_key=API_KEY,
        retry=RetryPolicy(max_attempts=1, sleep=_no_sleep),
        breaker=CircuitBreaker(threshold=99, reset_after_s=60),
        dimensions=dimensions,
    )


async def classify(provider: HttpLLMProvider) -> Classification:
    return await provider.structured_response(
        messages=[{"role": "user", "content": "I was charged twice"}],
        schema=Classification,
        max_output_tokens=500,
        timeout_s=5,
    )


class TestSuccessPath:
    async def test_validates_the_model_output_into_the_requested_schema(self) -> None:
        provider = build_llm(lambda _request: chat_response(VALID_CLASSIFICATION))

        result = await classify(provider)

        assert isinstance(result, Classification)
        assert result.category is TicketCategory.BILLING
        assert result.confidence == 0.9

    async def test_sends_the_model_schema_and_output_cap_to_the_provider(self) -> None:
        seen: dict[str, object] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            seen.update(json.loads(request.content))
            seen["auth"] = request.headers["Authorization"]
            return chat_response(VALID_CLASSIFICATION)

        await classify(build_llm(handler))

        assert seen["model"] == "test-model"
        assert seen["max_tokens"] == 500
        assert seen["response_format"]["type"] == "json_schema"
        assert seen["response_format"]["json_schema"]["name"] == "Classification"
        assert seen["auth"] == f"Bearer {API_KEY.get_secret_value()}"


class TestControlledErrors:
    async def test_timeout_raises_a_controlled_timeout_error(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ReadTimeout("too slow", request=request)

        with pytest.raises(AiProviderError) as caught:
            await classify(build_llm(handler))

        assert caught.value.code is AiErrorCode.TIMEOUT
        assert caught.value.retryable

    async def test_malformed_json_raises_a_controlled_invalid_output_error(self) -> None:
        provider = build_llm(lambda _request: chat_response("{not valid json"))

        with pytest.raises(AiProviderError) as caught:
            await classify(provider)

        assert caught.value.code is AiErrorCode.INVALID_OUTPUT

    async def test_schema_violating_output_is_rejected_with_the_offending_fields(self) -> None:
        # Well-formed JSON, but confidence is out of the documented [0,1] range.
        bad = {**VALID_CLASSIFICATION, "confidence": 4.2}
        provider = build_llm(lambda _request: chat_response(bad))

        with pytest.raises(AiProviderError) as caught:
            await classify(provider)

        assert caught.value.code is AiErrorCode.INVALID_OUTPUT
        assert "confidence" in str(caught.value)

    async def test_a_response_missing_the_expected_envelope_is_invalid_output(self) -> None:
        provider = build_llm(lambda _request: httpx.Response(200, json={"unexpected": True}))

        with pytest.raises(AiProviderError) as caught:
            await classify(provider)

        assert caught.value.code is AiErrorCode.INVALID_OUTPUT

    async def test_rate_limiting_is_reported_as_retryable(self) -> None:
        provider = build_llm(lambda _request: httpx.Response(429, text="slow down"))

        with pytest.raises(AiProviderError) as caught:
            await classify(provider)

        assert caught.value.code is AiErrorCode.RATE_LIMITED
        assert caught.value.retryable

    async def test_server_errors_are_retryable_but_client_errors_are_not(self) -> None:
        with pytest.raises(AiProviderError) as server_error:
            await classify(build_llm(lambda _r: httpx.Response(503, text="unavailable")))
        with pytest.raises(AiProviderError) as client_error:
            await classify(build_llm(lambda _r: httpx.Response(400, text="bad model id")))

        assert server_error.value.retryable
        # Retrying an identical malformed request only burns budget.
        assert not client_error.value.retryable

    async def test_unreachable_provider_is_a_controlled_error(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("no route to host", request=request)

        with pytest.raises(AiProviderError) as caught:
            await classify(build_llm(handler))

        assert caught.value.code is AiErrorCode.PROVIDER_ERROR
        assert caught.value.retryable


class TestBudgets:
    async def test_refuses_an_over_budget_request_without_calling_the_provider(self) -> None:
        called = False

        def handler(_request: httpx.Request) -> httpx.Response:
            nonlocal called
            called = True
            return chat_response(VALID_CLASSIFICATION)

        provider = build_llm(handler, max_input_tokens=10)

        with pytest.raises(AiProviderError) as caught:
            await provider.structured_response(
                messages=[{"role": "user", "content": "word " * 500}],
                schema=Classification,
                max_output_tokens=100,
                timeout_s=5,
            )

        assert caught.value.code is AiErrorCode.INPUT_TOO_LARGE
        assert not called, "an over-budget request must cost nothing"

    async def test_retries_a_retryable_failure_up_to_the_attempt_budget(self) -> None:
        attempts = 0

        def handler(_request: httpx.Request) -> httpx.Response:
            nonlocal attempts
            attempts += 1
            if attempts < 3:
                return httpx.Response(503, text="unavailable")
            return chat_response(VALID_CLASSIFICATION)

        result = await classify(build_llm(handler, max_attempts=3))

        assert attempts == 3
        assert result.category is TicketCategory.BILLING

    async def test_stops_at_the_attempt_budget_rather_than_retrying_forever(self) -> None:
        attempts = 0

        def handler(_request: httpx.Request) -> httpx.Response:
            nonlocal attempts
            attempts += 1
            return httpx.Response(503, text="unavailable")

        with pytest.raises(AiProviderError):
            await classify(build_llm(handler, max_attempts=3))

        assert attempts == 3

    async def test_does_not_retry_a_failure_that_would_fail_identically(self) -> None:
        attempts = 0

        def handler(_request: httpx.Request) -> httpx.Response:
            nonlocal attempts
            attempts += 1
            return httpx.Response(400, text="bad model id")

        with pytest.raises(AiProviderError):
            await classify(build_llm(handler, max_attempts=3))

        assert attempts == 1

    async def test_circuit_opens_after_repeated_failures_and_stops_calling(self) -> None:
        attempts = 0

        def handler(_request: httpx.Request) -> httpx.Response:
            nonlocal attempts
            attempts += 1
            return httpx.Response(500, text="boom")

        provider = build_llm(handler, max_attempts=1, threshold=2)

        for _ in range(2):
            with pytest.raises(AiProviderError):
                await classify(provider)
        assert attempts == 2

        # Third call must be refused locally — no further request reaches the provider.
        with pytest.raises(AiProviderError) as caught:
            await classify(provider)

        assert caught.value.code is AiErrorCode.CIRCUIT_OPEN
        assert attempts == 2

    async def test_a_success_closes_the_circuit_again(self) -> None:
        responses = [httpx.Response(500, text="boom"), chat_response(VALID_CLASSIFICATION)]

        def handler(_request: httpx.Request) -> httpx.Response:
            return responses.pop(0)

        provider = build_llm(handler, max_attempts=1, threshold=2)

        with pytest.raises(AiProviderError):
            await classify(provider)
        await classify(provider)  # success resets the consecutive-failure count

        assert responses == []


class TestEmbeddings:
    @staticmethod
    def _embedding_response(*vectors: list[float]) -> httpx.Response:
        return httpx.Response(200, json={"data": [{"embedding": v} for v in vectors]})

    async def test_returns_vectors_of_the_configured_width(self) -> None:
        provider = build_embedder(
            lambda _r: self._embedding_response([0.1] * 1536, [0.2] * 1536), dimensions=1536
        )

        vectors = await provider.embed(["a", "b"])

        assert [len(v) for v in vectors] == [1536, 1536]

    async def test_rejects_a_vector_the_schema_cannot_store(self) -> None:
        # docs/DATA_MODEL.md pins `vector(1536)`; a 768-wide vector cannot be written at all.
        provider = build_embedder(lambda _r: self._embedding_response([0.1] * 768), dimensions=1536)

        with pytest.raises(AiProviderError) as caught:
            await provider.embed(["a"])

        assert caught.value.code is AiErrorCode.DIMENSION_MISMATCH
        assert "768" in str(caught.value)

    async def test_rejects_a_response_with_the_wrong_number_of_vectors(self) -> None:
        provider = build_embedder(lambda _r: self._embedding_response([0.1] * 1536))

        with pytest.raises(AiProviderError) as caught:
            await provider.embed(["a", "b"])

        assert caught.value.code is AiErrorCode.INVALID_OUTPUT

    async def test_rejects_a_malformed_embedding_envelope(self) -> None:
        provider = build_embedder(lambda _r: httpx.Response(200, json={"nope": []}))

        with pytest.raises(AiProviderError) as caught:
            await provider.embed(["a"])

        assert caught.value.code is AiErrorCode.INVALID_OUTPUT

    async def test_embedding_nothing_calls_nothing(self) -> None:
        called = False

        def handler(_request: httpx.Request) -> httpx.Response:
            nonlocal called
            called = True
            return self._embedding_response()

        assert await build_embedder(handler).embed([]) == []
        assert not called

    async def test_reports_its_model_id_for_the_chunk_record(self) -> None:
        provider = build_embedder(lambda _r: self._embedding_response([0.1] * 1536))

        assert provider.model_id == "test-embed-model"


class TestSecretsNeverLeak:
    async def test_a_provider_error_body_echoing_the_key_is_redacted(self) -> None:
        leaky = f"invalid api key: {API_KEY.get_secret_value()}"
        provider = build_llm(lambda _r: httpx.Response(401, text=leaky))

        with pytest.raises(AiProviderError) as caught:
            await classify(provider)

        message = str(caught.value)
        assert API_KEY.get_secret_value() not in message
        assert "***redacted***" in message

    async def test_an_authorization_header_echoed_back_is_redacted(self) -> None:
        provider = build_llm(
            lambda _r: httpx.Response(400, text="rejected header Authorization: Bearer abc123xyz")
        )

        with pytest.raises(AiProviderError) as caught:
            await classify(provider)

        assert "abc123xyz" not in str(caught.value)
