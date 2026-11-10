"""Builds the provider pair the rest of the worker uses, from configuration alone.

Callers ask for providers, never for a mode. `AI_MODE` is read in exactly one
place — here — so no downstream code can accidentally branch on it, and nothing
can reach a real provider in mock mode by going around this module.
"""

from __future__ import annotations

import httpx

from opspilot_agent.config import AiMode, Settings
from opspilot_agent.providers.errors import AiNotConfiguredError
from opspilot_agent.providers.http import HttpEmbeddingProvider, HttpLLMProvider
from opspilot_agent.providers.mock import MockEmbeddingProvider, MockLLMProvider
from opspilot_agent.providers.protocols import EmbeddingProvider, LLMProvider
from opspilot_agent.providers.resilience import CircuitBreaker, RetryPolicy
from opspilot_agent.providers.usage import UsageLedger


def build_llm_provider(
    settings: Settings,
    *,
    client: httpx.AsyncClient | None = None,
    usage_ledger: UsageLedger | None = None,
) -> LLMProvider:
    if settings.ai_mode is AiMode.MOCK:
        return MockLLMProvider()

    model_id = _require(settings.llm_model_id, "LLM_MODEL_ID")
    return HttpLLMProvider(
        client=client or _build_client(settings),
        model_id=model_id,
        api_key=_require_api_key(settings),
        retry=_build_retry(settings),
        breaker=_build_breaker(settings),
        max_input_tokens=settings.agent_max_input_tokens,
        usage_ledger=usage_ledger,
    )


def build_embedding_provider(
    settings: Settings,
    *,
    client: httpx.AsyncClient | None = None,
    usage_ledger: UsageLedger | None = None,
) -> EmbeddingProvider:
    if settings.ai_mode is AiMode.MOCK:
        return MockEmbeddingProvider(dimensions=settings.embedding_dimensions)

    model_id = _require(settings.embedding_model_id, "EMBEDDING_MODEL_ID")
    return HttpEmbeddingProvider(
        client=client or _build_client(settings),
        model_id=model_id,
        api_key=_require_api_key(settings),
        retry=_build_retry(settings),
        breaker=_build_breaker(settings),
        dimensions=settings.embedding_dimensions,
        usage_ledger=usage_ledger,
    )


def _build_client(settings: Settings) -> httpx.AsyncClient:
    return httpx.AsyncClient(base_url=settings.llm_base_url, timeout=settings.llm_timeout_seconds)


def _build_retry(settings: Settings) -> RetryPolicy:
    return RetryPolicy(max_attempts=settings.llm_max_attempts)


def _build_breaker(settings: Settings) -> CircuitBreaker:
    return CircuitBreaker(
        threshold=settings.llm_circuit_breaker_threshold,
        reset_after_s=settings.llm_circuit_breaker_reset_seconds,
    )


def _require(value: str | None, env_name: str) -> str:
    if not value:
        raise AiNotConfiguredError(f"AI_MODE=real requires {env_name} to be set.")
    return value


def _require_api_key(settings: Settings):
    if settings.llm_api_key is None or not settings.llm_api_key.get_secret_value():
        raise AiNotConfiguredError("AI_MODE=real requires LLM_API_KEY to be set.")
    return settings.llm_api_key
