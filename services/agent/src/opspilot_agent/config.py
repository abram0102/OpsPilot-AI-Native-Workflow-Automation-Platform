"""Environment-backed configuration for the OpsPilot AI worker.

Fails fast with a clear error if a required value is missing for the
selected mode (AGENTS.md rule 9: no silent misconfiguration). `AI_MODE=mock`
— the default for local development and CI — needs no LLM API key at all;
only `AI_MODE=real` requires one, and that is checked explicitly here rather
than surfacing as an opaque error deep inside a provider call.
"""

from __future__ import annotations

from decimal import Decimal
from enum import StrEnum
from functools import lru_cache

from pydantic import SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class AiMode(StrEnum):
    MOCK = "mock"
    REAL = "real"


class Settings(BaseSettings):
    """Values match the names in the repository root `.env.example`."""

    model_config = SettingsConfigDict(env_file=None, extra="ignore")

    ai_mode: AiMode = AiMode.MOCK

    agent_health_port: int = 8000
    agent_max_steps: int = 12
    agent_max_input_tokens: int = 8000
    agent_max_output_tokens: int = 2000
    agent_tool_timeout_seconds: int = 30
    # T19 bounds a run by steps, tokens *and* time. The first two had settings already; the data
    # model and architecture both ask for a time budget without naming one, so this is it.
    agent_max_run_seconds: float = 120.0

    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_stream_name: str = "opspilot:events"
    redis_consumer_group: str = "agent-workers"

    postgres_host: str = "localhost"
    postgres_port: int = 5432
    postgres_db: str = "opspilot"
    postgres_user: str = "opspilot"
    # SecretStr for the same reason as the API key: a settings dump must not carry it.
    postgres_password: SecretStr | None = None

    # Where the Java local storage adapter writes uploaded documents (T13). The indexer reads
    # the same root; see docs/DECISIONS.md ADR-040.
    storage_local_dir: str = ".local-storage"

    # docs/PRODUCT_AND_ARCHITECTURE.md: "~600 tokens with ~100 overlap". Approximate by
    # construction — this service has no tokenizer (ADR-039).
    chunk_tokens: int = 600
    chunk_overlap_tokens: int = 100

    llm_provider: str = "openai"
    llm_base_url: str = "https://api.openai.com/v1"
    llm_model_id: str | None = None
    # SecretStr, not str: a settings dump, log line or traceback then shows `**********` rather
    # than the key (docs/SECURITY_AND_TESTING.md, "mask secrets/PII in logs").
    llm_api_key: SecretStr | None = None
    embedding_model_id: str | None = None
    embedding_dimensions: int = 1536
    llm_monthly_budget_usd: float = 0

    # Cost is only ever computed from rates an operator supplied. Unset means a recorded cost of
    # `null` rather than a guess — T20's acceptance says cost must be "recorded, not made up",
    # and prices change per model, region and account (docs/DECISIONS.md ADR-044).
    llm_input_cost_per_mtok: Decimal | None = None
    llm_output_cost_per_mtok: Decimal | None = None

    # Two independent locks on paid usage. `AI_MODE=real` alone is not enough: this flag has to
    # be set deliberately, so a stray environment cannot start spending money.
    allow_real_llm_calls: bool = False
    eval_max_cases: int = 3

    # Per-call budgets for the real adapter (T15). Mock mode ignores all of these.
    llm_timeout_seconds: float = 30.0
    llm_max_attempts: int = 3
    llm_circuit_breaker_threshold: int = 5
    llm_circuit_breaker_reset_seconds: float = 60.0

    @model_validator(mode="after")
    def _require_provider_credentials_outside_mock_mode(self) -> Settings:
        has_api_key = self.llm_api_key is not None and bool(self.llm_api_key.get_secret_value())
        if self.ai_mode is AiMode.REAL and not has_api_key:
            raise ValueError(
                "AI_MODE=real requires LLM_API_KEY to be set (see docs/MANUAL_SETUP.md Gate "
                "H2). Use AI_MODE=mock for local development and CI; no API key is needed there."
            )
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
