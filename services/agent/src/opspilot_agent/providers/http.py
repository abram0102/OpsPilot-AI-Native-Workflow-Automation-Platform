"""Real provider adapters, driven by environment configuration.

Written against the widely-implemented OpenAI-style REST shape
(`POST /chat/completions`, `POST /embeddings`) using plain `httpx` rather than a
vendor SDK. Two reasons: `LLM_PROVIDER` is an env knob, so several hosted and
self-hosted backends expose this same shape and one adapter serves them all; and
a thin adapter keeps every failure path — timeout, rate limit, malformed body,
wrong vector width — visible and testable here instead of behind an SDK's own
retry machinery.

The `httpx.AsyncClient` is injected, which is what lets the tests drive every one
of those paths through `httpx.MockTransport` without a network or a key.

**Not verified against a live provider.** No API key exists in this environment
(Gate H2, docs/MANUAL_SETUP.md), and making a real call would incur paid usage
that AGENTS.md rule 15 reserves for an explicit human decision. Live wiring, the
opt-in eval and per-run budget counters are T20's. What *is* verified here is
every behaviour that does not need a provider: request shaping, the error
taxonomy, retry budget, circuit breaking, dimension checking and redaction.
"""

from __future__ import annotations

import json
from typing import Any

import httpx
from pydantic import BaseModel, SecretStr, ValidationError

from opspilot_agent.providers.errors import (
    AiCallFailedError,
    AiDimensionMismatchError,
    AiInvalidOutputError,
    AiRateLimitedError,
    AiTimeoutError,
)
from opspilot_agent.providers.redaction import redact
from opspilot_agent.providers.resilience import CircuitBreaker, RetryPolicy
from opspilot_agent.providers.tokens import enforce_input_budget
from opspilot_agent.providers.usage import UsageLedger, usage_from_response

_MAX_ERROR_BODY_CHARS = 500
"""Provider error bodies are echoed into our own error message; cap and scrub them first."""


class _HttpProviderBase:
    def __init__(
        self,
        *,
        client: httpx.AsyncClient,
        model_id: str,
        api_key: SecretStr,
        retry: RetryPolicy,
        breaker: CircuitBreaker,
        usage_ledger: UsageLedger | None = None,
    ) -> None:
        self._client = client
        self._model_id = model_id
        self._api_key = api_key
        self._retry = retry
        self._breaker = breaker
        # Optional: a run supplies one to collect what its calls actually cost (T20). Absent
        # here, nothing is recorded — the adapter never invents usage it was not told.
        self._usage_ledger = usage_ledger

    @property
    def model_id(self) -> str:
        return self._model_id

    def _headers(self) -> dict[str, str]:
        # The one place the secret is revealed. It is never logged, formatted or echoed anywhere
        # else; `SecretStr` renders as a placeholder everywhere except here.
        return {
            "Authorization": f"Bearer {self._api_key.get_secret_value()}",
            "Content-Type": "application/json",
        }

    async def _post(self, path: str, payload: dict[str, Any], timeout_s: float) -> dict[str, Any]:
        """One attempt. Maps every provider/transport failure onto the error taxonomy."""
        try:
            response = await self._client.post(
                path, json=payload, headers=self._headers(), timeout=timeout_s
            )
        except httpx.TimeoutException as error:
            raise AiTimeoutError(f"Provider did not respond within {timeout_s}s.") from error
        except httpx.TransportError as error:
            raise AiCallFailedError(
                f"Could not reach the provider: {type(error).__name__}.", retryable=True
            ) from error

        if response.status_code == 429:
            raise AiRateLimitedError("Provider rate-limited the request.")
        if response.status_code >= 500:
            raise AiCallFailedError(
                f"Provider returned {response.status_code}: {self._safe_body(response)}",
                retryable=True,
            )
        if response.status_code >= 400:
            # 4xx is a request we built wrong (bad model id, revoked key). Retrying sends the
            # identical request, so it would only burn the budget.
            raise AiCallFailedError(
                f"Provider rejected the request with {response.status_code}: "
                f"{self._safe_body(response)}",
                retryable=False,
            )

        try:
            return response.json()
        except (json.JSONDecodeError, ValueError) as error:
            raise AiInvalidOutputError("Provider returned a body that is not JSON.") from error

    def _safe_body(self, response: httpx.Response) -> str:
        return redact(response.text[:_MAX_ERROR_BODY_CHARS], self._api_key)

    async def _call(self, path: str, payload: dict[str, Any], timeout_s: float) -> dict[str, Any]:
        """A full budgeted call: refuse if the breaker is open, retry, record the outcome."""
        self._breaker.raise_if_open()
        try:
            result = await self._retry.run(lambda: self._post(path, payload, timeout_s))
        except Exception:
            self._breaker.record_failure()
            raise
        self._breaker.record_success()
        return result


class HttpLLMProvider(_HttpProviderBase):
    """Chat-completions adapter returning validated structured output."""

    def __init__(self, *, max_input_tokens: int, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self._max_input_tokens = max_input_tokens

    async def structured_response(
        self,
        *,
        messages: list[dict[str, str]],
        schema: type[BaseModel],
        max_output_tokens: int,
        timeout_s: float,
    ) -> Any:
        # Refuse an over-budget request before it costs anything.
        enforce_input_budget(messages, self._max_input_tokens)

        payload = {
            "model": self._model_id,
            "messages": messages,
            "max_tokens": max_output_tokens,
            "response_format": {
                "type": "json_schema",
                "json_schema": {"name": schema.__name__, "schema": schema.model_json_schema()},
            },
        }

        body = await self._call("/chat/completions", payload, timeout_s)
        if self._usage_ledger is not None:
            self._usage_ledger.record(
                usage_from_response(body, kind="chat", requested_model=self._model_id)
            )
        return _parse_structured_output(body, schema)


class HttpEmbeddingProvider(_HttpProviderBase):
    """Embeddings adapter that refuses vectors the schema cannot store."""

    def __init__(self, *, dimensions: int, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self._dimensions = dimensions

    @property
    def dimensions(self) -> int:
        return self._dimensions

    async def embed(self, texts: list[str], *, timeout_s: float = 30.0) -> list[list[float]]:
        if not texts:
            return []

        payload = {"model": self._model_id, "input": texts}
        body = await self._call("/embeddings", payload, timeout_s)
        if self._usage_ledger is not None:
            self._usage_ledger.record(
                usage_from_response(body, kind="embedding", requested_model=self._model_id)
            )
        return self._parse_embeddings(body, expected_count=len(texts))

    def _parse_embeddings(self, body: dict[str, Any], *, expected_count: int) -> list[list[float]]:
        try:
            rows = body["data"]
            vectors = [list(row["embedding"]) for row in rows]
        except (KeyError, TypeError) as error:
            raise AiInvalidOutputError(
                "Embedding response did not contain a `data[].embedding` array."
            ) from error

        if len(vectors) != expected_count:
            raise AiInvalidOutputError(
                f"Asked for {expected_count} embeddings but the provider returned {len(vectors)}."
            )

        # docs/DATA_MODEL.md stores these in a `vector(1536)` column: a vector of any other width
        # cannot be written at all, so it is caught here rather than as a database error later.
        for index, vector in enumerate(vectors):
            if len(vector) != self._dimensions:
                raise AiDimensionMismatchError(
                    f"Embedding {index} has {len(vector)} dimensions, but this deployment is "
                    f"configured for {self._dimensions} (model {self._model_id})."
                )
        return vectors


def _parse_structured_output(body: dict[str, Any], schema: type[BaseModel]) -> Any:
    try:
        content = body["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as error:
        raise AiInvalidOutputError(
            "Chat response did not contain `choices[0].message.content`."
        ) from error

    if not isinstance(content, str):
        raise AiInvalidOutputError("Chat response content was not a string.")

    try:
        parsed = json.loads(content)
    except json.JSONDecodeError as error:
        raise AiInvalidOutputError(
            "Model output was not valid JSON, so it could not be validated against "
            f"{schema.__name__}."
        ) from error

    try:
        return schema.model_validate(parsed)
    except ValidationError as error:
        # The model's own text is untrusted and may be long; report the field-level reasons only.
        raise AiInvalidOutputError(
            f"Model output did not match {schema.__name__}: {error.error_count()} validation "
            f"error(s) on {_error_fields(error)}."
        ) from error


def _error_fields(error: ValidationError) -> str:
    fields = {".".join(str(part) for part in item["loc"]) or "(root)" for item in error.errors()}
    return ", ".join(sorted(fields))
