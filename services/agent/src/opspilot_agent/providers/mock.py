"""Deterministic provider implementations used whenever `AI_MODE=mock`.

No network call is ever made here — that is the whole point: CI and local
tests never need a real LLM/embedding API key (AGENTS.md rule 16). Real
classification, drafting, retrieval-grounding and budget logic is built in
ticket T15 on top of the `LLMProvider`/`EmbeddingProvider` protocols; these
mocks only need to satisfy that shape, not be useful on their own.
"""

from __future__ import annotations

import hashlib
from typing import Any

from opspilot_agent.config import Settings


class MockLLMProvider:
    """Returns a zero-initialized instance of the requested schema.

    `model_construct()` skips validation deliberately: this ticket has no
    real schemas to validate against yet (those arrive with the graph in
    T19), so the mock only needs to prove the protocol shape works end to
    end without ever reaching a network provider.
    """

    async def structured_response(
        self,
        *,
        messages: list[dict[str, str]],
        schema: type[Any],
        max_output_tokens: int,
        timeout_s: float,
    ) -> Any:
        return schema.model_construct()


class MockEmbeddingProvider:
    """Hashes each text into a fixed-dimension vector, deterministically.

    This makes retrieval reproducible across test runs without calling any
    network embedding provider; it is not a semantically meaningful
    embedding, which is fine for a provider that exists only to exercise the
    protocol in tests ahead of T15/T16's real implementation.
    """

    def __init__(self, dimensions: int) -> None:
        self._dimensions = dimensions

    async def embed(self, texts: list[str]) -> list[list[float]]:
        return [self._deterministic_vector(text) for text in texts]

    def _deterministic_vector(self, text: str) -> list[float]:
        digest = hashlib.sha256(text.encode("utf-8")).digest()
        return [(digest[i % len(digest)] / 255.0) * 2 - 1 for i in range(self._dimensions)]


def build_mock_llm_provider() -> MockLLMProvider:
    return MockLLMProvider()


def build_mock_embedding_provider(settings: Settings) -> MockEmbeddingProvider:
    return MockEmbeddingProvider(dimensions=settings.embedding_dimensions)
