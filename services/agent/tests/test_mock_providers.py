"""The deterministic mock must be reproducible, schema-valid, and never touch the network."""

from __future__ import annotations

import socket

import pytest
from pydantic import BaseModel

from opspilot_agent.providers.errors import AiErrorCode, AiProviderError
from opspilot_agent.providers.mock import (
    MOCK_EMBEDDING_MODEL_ID,
    MockEmbeddingProvider,
    MockLLMProvider,
)
from opspilot_agent.providers.schemas import Classification, ReplyDraft, TicketCategory, Urgency


def _messages(text: str) -> list[dict[str, str]]:
    return [{"role": "system", "content": "ignored"}, {"role": "user", "content": text}]


async def _classify(text: str) -> Classification:
    return await MockLLMProvider().structured_response(
        messages=_messages(text), schema=Classification, max_output_tokens=500, timeout_s=5
    )


class TestMockClassification:
    async def test_produces_a_validated_classification_not_a_placeholder(self) -> None:
        result = await _classify("My package never arrived and tracking is stuck.")

        assert isinstance(result, Classification)
        assert result.category is TicketCategory.SHIPPING
        # Validated, not `model_construct`-ed: re-validating must succeed.
        Classification.model_validate(result.model_dump())

    async def test_is_reproducible_across_calls(self) -> None:
        text = "I was charged twice for my subscription invoice."

        first = await _classify(text)
        second = await _classify(text)

        assert first == second

    @pytest.mark.parametrize(
        ("text", "expected"),
        [
            ("Where is my delivery? The courier lost the parcel.", TicketCategory.SHIPPING),
            ("Please refund the duplicate charge on my invoice.", TicketCategory.BILLING),
            ("I cannot sign in, my password reset email never came.", TicketCategory.ACCOUNT),
            ("Do you have a brochure about your company history?", TicketCategory.OTHER),
        ],
    )
    async def test_routes_each_seeded_category(self, text: str, expected: TicketCategory) -> None:
        assert (await _classify(text)).category is expected

    async def test_reads_urgency_from_stated_impact(self) -> None:
        assert (await _classify("URGENT: account locked out")).urgency is Urgency.HIGH
        assert (await _classify("Just a question, no rush")).urgency is Urgency.LOW
        assert (await _classify("The invoice looks wrong")).urgency is Urgency.MEDIUM

    async def test_reports_low_confidence_when_nothing_matched(self) -> None:
        unmatched = await _classify("Lorem ipsum dolor sit amet.")
        matched = await _classify("refund the billing charge on my invoice payment")

        assert unmatched.category is TicketCategory.OTHER
        assert unmatched.confidence < matched.confidence
        # A keyword rule this crude must never claim certainty.
        assert matched.confidence < 1.0

    async def test_truncates_an_over_long_summary_to_the_schema_limit(self) -> None:
        result = await _classify("delivery " * 200)

        assert len(result.summary) <= 500

    async def test_handles_an_empty_ticket_without_failing_validation(self) -> None:
        result = await _classify("")

        assert result.summary == "(empty ticket)"


class TestMockReplyDraft:
    async def test_produces_a_validated_reply_draft(self) -> None:
        result = await MockLLMProvider().structured_response(
            messages=_messages("Where is my package?"),
            schema=ReplyDraft,
            max_output_tokens=500,
            timeout_s=5,
        )

        assert isinstance(result, ReplyDraft)
        assert len(result.body) <= 4000
        # Nothing has been retrieved, so the mock must not invent a citation.
        assert result.source_citations == []


class TestMockUnknownSchema:
    async def test_refuses_a_schema_it_has_no_seeded_case_for(self) -> None:
        class Unseeded(BaseModel):
            value: str

        with pytest.raises(AiProviderError) as caught:
            await MockLLMProvider().structured_response(
                messages=_messages("hi"), schema=Unseeded, max_output_tokens=10, timeout_s=1
            )

        # Better a clear error than a silently empty object that looks like a real answer.
        assert caught.value.code is AiErrorCode.INVALID_OUTPUT


class TestMockEmbeddings:
    async def test_is_deterministic_and_the_configured_width(self) -> None:
        provider = MockEmbeddingProvider(dimensions=1536)

        first = await provider.embed(["hello", "world"])
        second = await provider.embed(["hello", "world"])

        assert first == second
        assert [len(vector) for vector in first] == [1536, 1536]

    async def test_distinguishes_different_texts(self) -> None:
        vectors = await MockEmbeddingProvider(dimensions=64).embed(["alpha", "beta"])

        assert vectors[0] != vectors[1]

    async def test_varies_along_the_whole_vector(self) -> None:
        # A single 32-byte digest cycled to fill 1536 slots would repeat every 32 values, making
        # most of the vector redundant. Past the first digest the values must still change.
        [vector] = await MockEmbeddingProvider(dimensions=1536).embed(["alpha"])

        assert vector[:32] != vector[32:64]

    async def test_values_stay_in_the_normalized_range(self) -> None:
        [vector] = await MockEmbeddingProvider(dimensions=256).embed(["alpha"])

        assert all(-1.0 <= value <= 1.0 for value in vector)

    async def test_identifies_its_model_so_chunks_record_where_they_came_from(self) -> None:
        provider = MockEmbeddingProvider(dimensions=8)

        # docs/DATA_MODEL.md requires every chunk to record its embedding model; a mock-produced
        # vector must be distinguishable from a real one.
        assert provider.model_id == MOCK_EMBEDDING_MODEL_ID
        assert "mock" in provider.model_id

    async def test_rejects_a_nonsense_width(self) -> None:
        with pytest.raises(ValueError, match="dimensions must be positive"):
            MockEmbeddingProvider(dimensions=0)


class TestMockMakesNoNetworkCalls:
    """The acceptance criterion, enforced rather than assumed."""

    @pytest.fixture(autouse=True)
    def _ban_outbound_connections(self, monkeypatch: pytest.MonkeyPatch) -> None:
        # Block connecting, not socket creation: asyncio builds sockets for its own event loop
        # plumbing, so banning the constructor would fail the test harness rather than the code
        # under test. Nothing can reach a provider without connect().
        def explode(*args: object, **kwargs: object) -> None:
            raise AssertionError("Mock mode attempted a network connection.")

        monkeypatch.setattr(socket.socket, "connect", explode)
        monkeypatch.setattr(socket.socket, "connect_ex", explode)
        monkeypatch.setattr(socket, "create_connection", explode)

    async def test_the_full_mock_path_runs_with_sockets_disabled(self) -> None:
        classification = await _classify("My parcel is late and I need it urgently.")
        draft = await MockLLMProvider().structured_response(
            messages=_messages("My parcel is late."),
            schema=ReplyDraft,
            max_output_tokens=200,
            timeout_s=5,
        )
        vectors = await MockEmbeddingProvider(dimensions=1536).embed(["chunk one", "chunk two"])

        assert classification.category is TicketCategory.SHIPPING
        assert draft.body
        assert len(vectors) == 2
