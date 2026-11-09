"""Chunking is approximate by construction, but its invariants are not."""

from __future__ import annotations

import pytest

from opspilot_agent.knowledge.chunking import (
    DEFAULT_CHUNK_TOKENS,
    DEFAULT_OVERLAP_TOKENS,
    chunk_text,
)


def test_short_text_is_a_single_chunk() -> None:
    chunks = chunk_text("A refund takes five working days.")

    assert len(chunks) == 1
    assert chunks[0].index == 0
    assert chunks[0].estimated_tokens > 0


def test_empty_text_produces_no_chunks() -> None:
    assert chunk_text("") == []
    assert chunk_text("   \n\n  ") == []


def test_long_text_is_split_into_sequentially_indexed_chunks() -> None:
    text = " ".join(f"sentence number {i}." for i in range(2000))

    chunks = chunk_text(text)

    assert len(chunks) > 1
    assert [c.index for c in chunks] == list(range(len(chunks)))


def test_every_chunk_stays_near_the_requested_size() -> None:
    text = "word " * 5000

    chunks = chunk_text(text, chunk_tokens=100, overlap_tokens=20)

    # The boundary search can end a chunk early, never late, so the cap is the hard side.
    assert all(c.estimated_tokens <= 100 for c in chunks)


def test_consecutive_chunks_overlap_so_an_answer_spanning_a_boundary_survives() -> None:
    text = " ".join(f"token{i}" for i in range(4000))

    chunks = chunk_text(text, chunk_tokens=100, overlap_tokens=30)

    first_words = set(chunks[0].text.split())
    second_words = set(chunks[1].text.split())
    assert first_words & second_words, "chunks must share text at the boundary"


def test_no_text_is_lost_between_chunks() -> None:
    text = " ".join(f"w{i}" for i in range(3000))

    chunks = chunk_text(text, chunk_tokens=80, overlap_tokens=10)

    # Every original word must appear somewhere; overlap may repeat, but nothing may vanish.
    seen = set()
    for chunk in chunks:
        seen.update(chunk.text.split())
    assert seen.issuperset(set(text.split()))


def test_prefers_breaking_at_a_paragraph_boundary() -> None:
    paragraph = "This is a sentence about refunds. " * 10
    text = (paragraph + "\n\n") * 10

    chunks = chunk_text(text, chunk_tokens=120, overlap_tokens=10)

    # With paragraph breaks available in the window, most chunks should end at one rather than
    # mid-sentence.
    ending_cleanly = sum(1 for c in chunks if c.text.rstrip().endswith("."))
    assert ending_cleanly >= len(chunks) // 2


def test_normalizes_line_endings_and_collapses_blank_runs() -> None:
    chunks = chunk_text("alpha\r\n\r\n\r\n\r\nbeta")

    assert "\r" not in chunks[0].text
    assert "\n\n\n" not in chunks[0].text


def test_terminates_even_when_overlap_nearly_equals_chunk_size() -> None:
    # A regression guard: if `start` ever failed to advance, this would hang rather than fail.
    chunks = chunk_text("word " * 500, chunk_tokens=10, overlap_tokens=9)

    assert len(chunks) > 1


@pytest.mark.parametrize(
    ("chunk_tokens", "overlap_tokens"),
    [(0, 0), (-1, 0), (100, 100), (100, 150), (100, -1)],
)
def test_rejects_nonsense_parameters(chunk_tokens: int, overlap_tokens: int) -> None:
    with pytest.raises(ValueError):
        chunk_text("some text", chunk_tokens=chunk_tokens, overlap_tokens=overlap_tokens)


def test_defaults_match_the_documented_sizes() -> None:
    # docs/PRODUCT_AND_ARCHITECTURE.md: "~600 tokens with ~100 overlap".
    assert (DEFAULT_CHUNK_TOKENS, DEFAULT_OVERLAP_TOKENS) == (600, 100)
