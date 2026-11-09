"""Typed provider interfaces, matching docs/API_CONTRACT.md's Python protocols.

These are intentionally narrow: a provider turns messages into schema-shaped
structured output, or text into vectors. There is no generic "call any tool"
or "run arbitrary code" surface here or anywhere else in the agent (AGENTS.md
invariant: "No arbitrary SQL, code, shell commands or URLs may be generated
by the LLM and run as tools").

`model_id` is additive to the contract as written in docs/API_CONTRACT.md, and
it is not decoration: docs/DATA_MODEL.md requires every stored chunk to record
the embedding model that produced it ("re-embedding changes retrieval
correctness"), so a vector that cannot say where it came from is unusable to
the indexer in T16.
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
    @property
    def model_id(self) -> str:
        """The model that produced the vectors, for `knowledge_chunks.embedding_model`."""
        ...

    @property
    def dimensions(self) -> int:
        """Vector width this provider emits; must match the `vector(1536)` column in V003."""
        ...

    async def embed(self, texts: list[str]) -> list[list[float]]: ...
