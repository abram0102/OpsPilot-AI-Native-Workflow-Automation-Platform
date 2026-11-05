"""Typed provider interfaces, matching docs/API_CONTRACT.md's Python protocols.

These are intentionally narrow: a provider turns messages into schema-shaped
structured output, or text into vectors. There is no generic "call any tool"
or "run arbitrary code" surface here or anywhere else in the agent (AGENTS.md
invariant: "No arbitrary SQL, code, shell commands or URLs may be generated
by the LLM and run as tools").

Real adapters (timeouts, retries, token budgets, circuit breaking) are built
in ticket T15 on top of these protocols; this ticket (T03) only establishes
the shape and the deterministic mock implementations in `mock.py`.
"""

from __future__ import annotations

from typing import Any, Protocol


class LLMProvider(Protocol):
    async def structured_response(
        self,
        *,
        messages: list[dict[str, str]],
        schema: type[Any],
        max_output_tokens: int,
        timeout_s: float,
    ) -> Any: ...


class EmbeddingProvider(Protocol):
    async def embed(self, texts: list[str]) -> list[list[float]]: ...
