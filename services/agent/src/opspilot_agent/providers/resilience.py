"""Retry budget and circuit breaker for provider calls.

Hand-rolled rather than pulled from a library on purpose: the whole behaviour is
~60 lines, the semantics here are specific (only `AiProviderError.retryable`
failures are retried, and a breaker counts *consecutive* failures across calls),
and `tenacity` is currently only a transitive dependency — declaring a direct one
to save this much code is a poor trade (AGENTS.md rule 4).

Sleeping between attempts goes through an injectable `sleep`, so tests exercise
the real backoff decisions without actually waiting.
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import Awaitable, Callable
from typing import TypeVar

from opspilot_agent.providers.errors import AiCircuitOpenError, AiProviderError

T = TypeVar("T")

Sleeper = Callable[[float], Awaitable[None]]


class CircuitBreaker:
    """Opens after `threshold` consecutive failures and stays open for `reset_after_s`.

    The point is to stop paying for calls that are clearly failing: once open, calls are refused
    locally with `AI_CIRCUIT_OPEN` and no request is made at all. One success closes it again.
    """

    def __init__(
        self,
        *,
        threshold: int,
        reset_after_s: float,
        now: Callable[[], float] = time.monotonic,
    ) -> None:
        if threshold < 1:
            raise ValueError("Circuit breaker threshold must be at least 1.")
        self._threshold = threshold
        self._reset_after_s = reset_after_s
        self._now = now
        self._consecutive_failures = 0
        self._opened_at: float | None = None

    @property
    def is_open(self) -> bool:
        if self._opened_at is None:
            return False
        if self._now() - self._opened_at >= self._reset_after_s:
            # Cool-off elapsed: allow one trial call through rather than staying open forever.
            self._opened_at = None
            self._consecutive_failures = 0
            return False
        return True

    def record_success(self) -> None:
        self._consecutive_failures = 0
        self._opened_at = None

    def record_failure(self) -> None:
        self._consecutive_failures += 1
        if self._consecutive_failures >= self._threshold:
            self._opened_at = self._now()

    def raise_if_open(self) -> None:
        if self.is_open:
            raise AiCircuitOpenError(
                f"Refusing the call: {self._consecutive_failures} consecutive provider failures, "
                f"retrying in at most {self._reset_after_s:.0f}s."
            )


class RetryPolicy:
    """A bounded attempt budget with exponential backoff.

    `max_attempts` counts *total* attempts, not retries after the first, so 1 means "no retry".
    Only `retryable` provider errors are retried; a schema violation that will fail identically
    every time, or a refusal we raised ourselves, propagates immediately.
    """

    def __init__(
        self,
        *,
        max_attempts: int,
        backoff_base_s: float = 0.2,
        sleep: Sleeper | None = None,
    ) -> None:
        if max_attempts < 1:
            raise ValueError("max_attempts must be at least 1.")
        self._max_attempts = max_attempts
        self._backoff_base_s = backoff_base_s
        self._sleep: Sleeper = sleep or asyncio.sleep

    async def run(self, call: Callable[[], Awaitable[T]]) -> T:
        last_error: AiProviderError | None = None
        for attempt in range(1, self._max_attempts + 1):
            try:
                return await call()
            except AiProviderError as error:
                if not error.retryable or attempt == self._max_attempts:
                    raise
                last_error = error
                await self._sleep(self._backoff_base_s * (2 ** (attempt - 1)))
        raise last_error  # unreachable: the loop either returns or raises
