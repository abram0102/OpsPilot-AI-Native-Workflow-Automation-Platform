import pytest
from fastapi.testclient import TestClient

from opspilot_agent.config import get_settings
from opspilot_agent.main import app


@pytest.fixture(autouse=True)
def _clear_settings_cache() -> None:
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def test_liveness_returns_ok_without_any_configuration(monkeypatch: pytest.MonkeyPatch) -> None:
    # Liveness must never depend on configuration validity.
    monkeypatch.setenv("AI_MODE", "real")
    monkeypatch.delenv("LLM_API_KEY", raising=False)

    client = TestClient(app)
    response = client.get("/health/live")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_readiness_returns_ok_in_mock_mode(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_MODE", "mock")
    monkeypatch.delenv("LLM_API_KEY", raising=False)

    client = TestClient(app)
    response = client.get("/health/ready")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_readiness_fails_when_real_mode_is_misconfigured(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_MODE", "real")
    monkeypatch.delenv("LLM_API_KEY", raising=False)

    client = TestClient(app, raise_server_exceptions=False)
    response = client.get("/health/ready")

    assert response.status_code == 500
