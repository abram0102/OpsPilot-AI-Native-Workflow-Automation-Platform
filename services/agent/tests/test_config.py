import pytest

from opspilot_agent.config import AiMode, Settings


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
