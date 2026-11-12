from decimal import Decimal

import pytest

from opspilot_agent.config import AiMode, Settings

# docs/TASKS.md T28: part of the security regression suite (`make test-security`) —
# API key masking and real-mode preconditions.
pytestmark = pytest.mark.security


def test_mock_mode_needs_no_api_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("LLM_API_KEY", raising=False)
    monkeypatch.setenv("AI_MODE", "mock")

    settings = Settings()

    assert settings.ai_mode is AiMode.MOCK
    assert settings.llm_api_key is None


def test_real_mode_without_an_api_key_fails_fast(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("LLM_API_KEY", raising=False)
    monkeypatch.setenv("AI_MODE", "real")

    with pytest.raises(ValueError, match="AI_MODE=real requires LLM_API_KEY"):
        Settings()


def test_real_mode_succeeds_once_an_api_key_is_present(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_MODE", "real")
    monkeypatch.setenv("LLM_API_KEY", "test-only-key")

    settings = Settings()

    assert settings.ai_mode is AiMode.REAL
    assert settings.llm_api_key is not None
    assert settings.llm_api_key.get_secret_value() == "test-only-key"


def test_api_key_is_masked_when_settings_are_printed_or_dumped(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A settings object reaching a log line must not take the key with it."""
    monkeypatch.setenv("AI_MODE", "real")
    monkeypatch.setenv("LLM_API_KEY", "sk-super-secret-value")

    settings = Settings()

    assert "sk-super-secret-value" not in repr(settings)
    assert "sk-super-secret-value" not in str(settings)
    assert "sk-super-secret-value" not in str(settings.model_dump())


def test_defaults_match_env_example_values() -> None:
    settings = Settings()

    assert settings.embedding_dimensions == 1536
    assert settings.redis_stream_name == "opspilot:events"
    assert settings.redis_consumer_group == "agent-workers"
    assert settings.redis_dlq_stream_name == "opspilot:events:dlq"


def test_the_shipped_worker_timings_are_the_ones_the_tests_deliberately_compress() -> None:
    """T25's defaults, asserted here because every consumer test overrides them.

    Those tests shorten the lease, the heartbeat and the reclaim window so a reclaim is exercised
    rather than waited out for a minute. That makes this the only place the real values are
    checked — without it, a typo in a default would be invisible until production.
    """
    settings = Settings()

    assert settings.worker_lease_seconds == 60
    assert settings.worker_heartbeat_seconds == 15.0
    assert settings.worker_max_attempts == 5
    assert settings.worker_backoff_base_seconds == 1.0
    assert settings.worker_backoff_max_seconds == 60.0
    assert settings.redis_block_ms == 5000
    assert settings.redis_batch_size == 10

    # The invariant that ties the two mechanisms together: a message must not be reclaimable
    # before the lease on its run has expired, or the new owner could not take the lease.
    assert settings.worker_reclaim_min_idle_ms >= settings.worker_lease_seconds * 1000
    # And the lease must outlast several heartbeats, so one missed beat is not fatal.
    assert settings.worker_lease_seconds >= settings.worker_heartbeat_seconds * 3


def test_an_empty_cost_rate_in_the_environment_means_no_rate(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """`.env.example` ships `LLM_INPUT_COST_PER_MTOK=` with no value.

    `make worker-dev` sources that file, so the variable arrives *present but empty*. Pydantic sees
    the empty string, not an absent value, and `Decimal | None` rejected it — the worker died on
    startup before reading a single event. Found by actually running `make worker-dev`.
    """
    monkeypatch.setenv("AI_MODE", "mock")
    monkeypatch.setenv("LLM_INPUT_COST_PER_MTOK", "")
    monkeypatch.setenv("LLM_OUTPUT_COST_PER_MTOK", "   ")

    settings = Settings()

    assert settings.llm_input_cost_per_mtok is None
    assert settings.llm_output_cost_per_mtok is None


def test_a_real_cost_rate_is_still_parsed(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_MODE", "mock")
    monkeypatch.setenv("LLM_INPUT_COST_PER_MTOK", "3.50")

    assert Settings().llm_input_cost_per_mtok == Decimal("3.50")
