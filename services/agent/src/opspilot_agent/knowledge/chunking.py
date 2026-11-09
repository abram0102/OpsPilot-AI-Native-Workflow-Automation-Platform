"""Splitting an extracted document into overlapping chunks for embedding.

docs/PRODUCT_AND_ARCHITECTURE.md specifies "~600 tokens with ~100 overlap". The
tilde is doing real work: this service still has no tokenizer (ADR-039), so the
sizes here are computed from the same documented character-length estimate T15
uses — about four characters per token. Chunk sizes are therefore approximate by
construction, and `token_count` on a stored chunk is an estimate, which is why
the column it feeds is named for a count but documented as one.

Splitting prefers natural boundaries over exact widths: a chunk that ends at a
paragraph or sentence break retrieves better than one cut mid-word, and the
overlap exists so an answer spanning a boundary is still found whole.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from opspilot_agent.providers.tokens import estimate_tokens

DEFAULT_CHUNK_TOKENS = 600
DEFAULT_OVERLAP_TOKENS = 100

_CHARS_PER_TOKEN = 4
"""Mirrors providers/tokens.py. An estimate, not a measurement."""

# Prefer to break at a blank line, then a line break, then a sentence end.
_BOUNDARY_PATTERNS = ("\n\n", "\n", ". ")


@dataclass(frozen=True)
class Chunk:
    index: int
    text: str
    estimated_tokens: int


def chunk_text(
    text: str,
    *,
    chunk_tokens: int = DEFAULT_CHUNK_TOKENS,
    overlap_tokens: int = DEFAULT_OVERLAP_TOKENS,
) -> list[Chunk]:
    """Splits `text` into overlapping chunks of roughly `chunk_tokens` each."""
    if chunk_tokens < 1:
        raise ValueError("chunk_tokens must be at least 1.")
    if not 0 <= overlap_tokens < chunk_tokens:
        raise ValueError("overlap_tokens must be non-negative and smaller than chunk_tokens.")

    normalized = _normalize(text)
    if not normalized:
        return []

    chunk_chars = chunk_tokens * _CHARS_PER_TOKEN
    overlap_chars = overlap_tokens * _CHARS_PER_TOKEN

    chunks: list[Chunk] = []
    start = 0
    while start < len(normalized):
        end = min(start + chunk_chars, len(normalized))
        if end < len(normalized):
            end = _best_boundary(normalized, start, end)

        piece = normalized[start:end].strip()
        if piece:
            chunks.append(Chunk(len(chunks), piece, estimate_tokens(piece)))

        if end >= len(normalized):
            break
        # Step forward by at least one character; the overlap must never push `start` backwards
        # or the loop would not terminate.
        start = max(end - overlap_chars, start + 1)

    return chunks


def _normalize(text: str) -> str:
    """Collapses runs of blank lines and trailing whitespace, preserving paragraph breaks."""
    without_crlf = text.replace("\r\n", "\n").replace("\r", "\n")
    collapsed = re.sub(r"\n{3,}", "\n\n", without_crlf)
    return "\n".join(line.rstrip() for line in collapsed.split("\n")).strip()


def _best_boundary(text: str, start: int, end: int) -> int:
    """The latest natural break in the back part of the window, or `end` if there is none."""
    # Only look in the last quarter: searching the whole window could return a boundary so early
    # that chunks come out far smaller than asked for.
    earliest = start + (end - start) * 3 // 4
    for pattern in _BOUNDARY_PATTERNS:
        found = text.rfind(pattern, earliest, end)
        if found != -1:
            return found + len(pattern)
    return end
