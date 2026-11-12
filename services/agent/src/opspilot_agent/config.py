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
from typing import Annotated

from pydantic import BeforeValidator, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class AiMode(StrEnum):
    MOCK = "mock"
    REAL = "real"


def _empty_string_as_none(value: object) -> object:
    """An env var set to the empty string means "not set"."""
    if isinstance(value, str) and not value.strip():
        return None
    return value


OptionalDecimal = Annotated[Decimal | None, BeforeValidator(_empty_string_as_none)]


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

    # T25. The consumer's own name inside the group; blank means "derive one from host and pid",
    # which is what makes two workers on one machine distinguishable without any configuration.
    # It matters beyond logging: XAUTOCLAIM reclaims entries *pending for another consumer*, so
    # two processes sharing a name would consider each other's in-flight work their own.
    redis_consumer_name: str = ""
    redis_dlq_stream_name: str = "opspilot:events:dlq"
    # How long one XREADGROUP waits for work. Long enough that an idle worker is not a busy loop,
    # short enough that a shutdown signal is noticed promptly.
    redis_block_ms: int = 5000
    redis_batch_size: int = 10

    # Retry policy for a *handler* that failed, not for a broken message: those are dead-lettered
    # immediately, since no number of retries will make them parseable.
    worker_max_attempts: int = 5
    worker_backoff_base_seconds: float = 1.0
    worker_backoff_max_seconds: float = 60.0

    # The per-run lease (agent_runs.lease_owner / lease_expires_at, columns V004 already created).
    # The TTL is deliberately a small multiple of the heartbeat: one missed heartbeat must not be
    # enough to have another worker declare the run abandoned.
    worker_lease_seconds: int = 60
    worker_heartbeat_seconds: float = 15.0
    # How long a stream entry must sit unacknowledged before another worker may claim it. Kept at
    # or above the lease TTL on purpose — reclaiming sooner than the lease expires would hand the
    # entry to a worker that then cannot take the lease.
    worker_reclaim_min_idle_ms: int = 60000

    postgres_host: str = "localhost"
    postgres_port: int = 5432
    postgres_db: str = "opspilot"
    postgres_user: str = "opspilot"
    # SecretStr for the same reason as the API key: a settings dump must not carry it.
    postgres_password: SecretStr | None = None

    # Where the Java local storage adapter writes uploaded documents (T13). The indexer reads
    # the same root; see docs/DECISIONS.md ADR-040.
    storage_local_dir: str = ".local-storage"

    # T23: the worker's own calls into services/api's private action endpoint. The audience and
    # secret must match the API's `app.security.internal-service.*`; the secret is SecretStr for
    # the same reason the LLM key is.
    api_base_url: str = "http://localhost:8080"
    internal_service_audience: str = "https://internal.opspilot.local"
    internal_service_signing_secret: SecretStr | None = None
    internal_service_token_ttl_seconds: int = 300

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
    #
    # `OptionalDecimal`, not `Decimal | None`: an environment variable that is PRESENT BUT EMPTY
    # (`LLM_INPUT_COST_PER_MTOK=`, exactly as .env.example ships it) reaches pydantic as the empty
    # string, which is not a valid decimal, so the worker refused to start at all. "Set to nothing"
    # and "not set" mean the same thing for a rate, and both must mean "no rate".
    llm_input_cost_per_mtok: OptionalDecimal = None
    llm_output_cost_per_mtok: OptionalDecimal = None

    # Two independent locks on paid usage. `AI_MODE=real` alone is not enough: this flag has to
    # be set deliberately, so a stray environment cannot start spending money.
    allow_real_llm_calls: bool = False
    eval_max_cases: int = 3

    # Per-call budgets for the real adapter (T15). Mock mode ignores all of these.
    llm_timeout_seconds: float = 30.0
    llm_max_attempts: int = 3
    llm_circuit_breaker_threshold: int = 5
    llm_circuit_breaker_reset_seconds: float = 60.0

    # T30, optional error reporting. Empty means off, which is the default and the only state
    # this repository is ever tested in with a real destination. `sentry-sdk` is deliberately not
    # a dependency — see observability.configure_error_reporting for why, and for what happens if
    # this is set without it installed.
    sentry_dsn: str | None = None

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
