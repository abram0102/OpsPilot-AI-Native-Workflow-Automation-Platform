"""Stable error codes for the AI provider layer.

Every failure in this layer surfaces as one of these, never as a raw provider
exception or a bare `ValueError`. Callers (the graph in T19, the worker in T25)
branch on `code`, and a run's terminal failure records it — so these strings are
part of the contract and are not reworded casually.

Nothing here ever carries the API key. `__str__` is what lands in logs and
tracebacks, so the message is built from the caller's own inputs only; see
`redact` for the one place provider-supplied text is passed through.
"""

from __future__ import annotations

from enum import StrEnum


class AiErrorCode(StrEnum):
    TIMEOUT = "AI_TIMEOUT"
    """The provider did not answer within the configured per-attempt timeout."""

    INVALID_OUTPUT = "AI_INVALID_OUTPUT"
    """The provider answered, but not with output matching the requested schema."""

    RATE_LIMITED = "AI_RATE_LIMITED"
    """The provider refused the call with a retryable rate-limit/overload signal."""

    PROVIDER_ERROR = "AI_PROVIDER_ERROR"
    """The provider failed for some other reason (non-2xx, transport failure)."""

    CIRCUIT_OPEN = "AI_CIRCUIT_OPEN"
    """Refused locally: too many consecutive failures, so no call was attempted."""

    INPUT_TOO_LARGE = "AI_INPUT_TOO_LARGE"
    """Refused locally: the request exceeds the configured input budget."""

    DIMENSION_MISMATCH = "AI_DIMENSION_MISMATCH"
    """An embedding came back with a width the schema cannot store."""

    NOT_CONFIGURED = "AI_NOT_CONFIGURED"
    """A real provider was requested without the configuration it needs."""


class AiProviderError(Exception):
    """Base for every failure this layer raises."""

    def __init__(self, code: AiErrorCode, message: str, *, retryable: bool = False) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable

    def __str__(self) -> str:
        return f"[{self.code}] {self.message}"


class AiTimeoutError(AiProviderError):
    def __init__(self, message: str) -> None:
        super().__init__(AiErrorCode.TIMEOUT, message, retryable=True)


class AiInvalidOutputError(AiProviderError):
    def __init__(self, message: str) -> None:
        # Retryable on purpose: docs/API_CONTRACT.md says invalid structured output causes a
        # "controlled retry, then terminal failure" — a model that returned malformed JSON once
        # will often return valid JSON on a second attempt.
        super().__init__(AiErrorCode.INVALID_OUTPUT, message, retryable=True)


class AiRateLimitedError(AiProviderError):
    def __init__(self, message: str) -> None:
        super().__init__(AiErrorCode.RATE_LIMITED, message, retryable=True)


class AiCallFailedError(AiProviderError):
    def __init__(self, message: str, *, retryable: bool = False) -> None:
        super().__init__(AiErrorCode.PROVIDER_ERROR, message, retryable=retryable)


class AiCircuitOpenError(AiProviderError):
    def __init__(self, message: str) -> None:
        super().__init__(AiErrorCode.CIRCUIT_OPEN, message)


class AiInputTooLargeError(AiProviderError):
    def __init__(self, message: str) -> None:
        super().__init__(AiErrorCode.INPUT_TOO_LARGE, message)


class AiDimensionMismatchError(AiProviderError):
    def __init__(self, message: str) -> None:
        super().__init__(AiErrorCode.DIMENSION_MISMATCH, message)


class AiNotConfiguredError(AiProviderError):
    def __init__(self, message: str) -> None:
        super().__init__(AiErrorCode.NOT_CONFIGURED, message)
