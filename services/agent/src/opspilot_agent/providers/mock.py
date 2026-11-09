"""Deterministic provider implementations used whenever `AI_MODE=mock`.

No network call is ever made here — that is the whole point: CI and local tests
never need a real LLM/embedding API key (AGENTS.md rule 16).

Two properties these mocks are built to guarantee, because tests downstream will
depend on them:

* **Deterministic.** The same input always produces the same output, with no
  clock, no RNG and no global state. A test can assert on exact values.
* **Schema-valid.** Output is constructed and then *validated* through the real
  Pydantic model, so a mock run exercises the same validation path a real
  provider's output would. (T03's placeholder used `model_construct()`, which
  skipped validation; a malformed mock could not have been caught.)

What they are not is a model. Classification comes from the keyword rules in
`seed_cases`, and the reply body is a template. Mock output is reproducible and
correctly shaped, never semantically clever.
"""

from __future__ import annotations

import hashlib
from typing import Any

from opspilot_agent.providers.errors import AiInvalidOutputError
from opspilot_agent.providers.schemas import Classification, ReplyDraft
from opspilot_agent.providers.seed_cases import classify_category, classify_urgency

MOCK_EMBEDDING_MODEL_ID = "mock-deterministic-sha256-v1"
"""Recorded in `knowledge_chunks.embedding_model`, so mock-indexed chunks are never mistaken
for real ones (docs/DATA_MODEL.md requires the producing model on every chunk)."""


class MockLLMProvider:
    """Produces reproducible, schema-valid structured output from seeded rules."""

    async def structured_response(
        self,
        *,
        messages: list[dict[str, str]],
        schema: type[Any],
        max_output_tokens: int,
        timeout_s: float,
    ) -> Any:
        prompt_text = _user_text(messages)

        if schema is Classification:
            return _mock_classification(prompt_text)
        if schema is ReplyDraft:
            return _mock_reply_draft(prompt_text)

        raise AiInvalidOutputError(
            f"The mock provider has no seeded case for schema {schema.__name__}. Add one to "
            "providers/mock.py, or run with AI_MODE=real."
        )


class MockEmbeddingProvider:
    """Hashes each text into a fixed-width vector, deterministically.

    Not a semantically meaningful embedding — similar texts get unrelated vectors. It exists so
    that retrieval plumbing is reproducible offline; judging retrieval *quality* needs a real
    provider and is what the opt-in eval in T20/T30 is for.
    """

    def __init__(self, dimensions: int) -> None:
        if dimensions < 1:
            raise ValueError("Embedding dimensions must be positive.")
        self._dimensions = dimensions

    @property
    def model_id(self) -> str:
        return MOCK_EMBEDDING_MODEL_ID

    @property
    def dimensions(self) -> int:
        return self._dimensions

    async def embed(self, texts: list[str]) -> list[list[float]]:
        return [self._deterministic_vector(text) for text in texts]

    def _deterministic_vector(self, text: str) -> list[float]:
        # Re-hash per block so the vector varies along its whole width; hashing once and cycling
        # the digest (as the T03 placeholder did) repeats every 32 values.
        values: list[float] = []
        block = 0
        while len(values) < self._dimensions:
            digest = hashlib.sha256(f"{block}:{text}".encode()).digest()
            values.extend((byte / 255.0) * 2 - 1 for byte in digest)
            block += 1
        return values[: self._dimensions]


def _user_text(messages: list[dict[str, str]]) -> str:
    """The caller's own content, ignoring system instructions."""
    return "\n".join(
        message.get("content", "") for message in messages if message.get("role") != "system"
    )


def _mock_classification(text: str) -> Classification:
    category, keyword_hits = classify_category(text)
    urgency = classify_urgency(text)

    # Confidence rises with the number of supporting keywords and is capped below 1.0 — a rule
    # this crude should never claim certainty.
    confidence = min(0.5 + 0.15 * keyword_hits, 0.95) if keyword_hits else 0.25

    summary = text.strip().replace("\n", " ")
    if len(summary) > 500:
        summary = summary[:497] + "..."

    return Classification(
        category=category,
        urgency=urgency,
        summary=summary or "(empty ticket)",
        confidence=round(confidence, 2),
    )


def _mock_reply_draft(text: str) -> ReplyDraft:
    category, _ = classify_category(text)
    body = (
        f"Thanks for getting in touch about your {category.value.lower()} issue. "
        "A support agent is reviewing the details and will follow up shortly."
    )
    # Citations stay empty by design: nothing has been retrieved. Grounded citations arrive once
    # the indexer (T16) and the graph that feeds it into a prompt (T19) exist.
    return ReplyDraft(body=body, source_citations=[])


def build_mock_llm_provider() -> MockLLMProvider:
    return MockLLMProvider()


def build_mock_embedding_provider(dimensions: int) -> MockEmbeddingProvider:
    return MockEmbeddingProvider(dimensions=dimensions)
