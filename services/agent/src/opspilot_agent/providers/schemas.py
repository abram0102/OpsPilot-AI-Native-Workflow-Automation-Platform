"""Structured outputs the LLM is allowed to produce (docs/API_CONTRACT.md).

Every field bound here is a real constraint, not documentation: a provider
response is parsed through these models before anything downstream sees it, so
an over-long summary or an out-of-range confidence is a controlled validation
failure rather than data that quietly reaches a ticket.

Only the shapes this ticket actually needs are defined. `RetrievedChunk` is
T16's (its `score` comes from pgvector) and `ToolProposal` is T19/T21's —
defining them here would be guessing at tickets that haven't been designed yet.
"""

from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field


class TicketCategory(StrEnum):
    SHIPPING = "SHIPPING"
    BILLING = "BILLING"
    ACCOUNT = "ACCOUNT"
    OTHER = "OTHER"


class Urgency(StrEnum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class Classification(BaseModel):
    """docs/API_CONTRACT.md: category/urgency enums, summary <= 500, confidence [0,1]."""

    model_config = ConfigDict(extra="forbid")

    category: TicketCategory
    urgency: Urgency
    summary: str = Field(max_length=500)
    confidence: float = Field(ge=0.0, le=1.0)


class ReplyDraft(BaseModel):
    """docs/API_CONTRACT.md: body <= 4000 chars, citations referencing retrieved chunks.

    `source_citations` holds chunk *identifiers* rather than whole chunk objects. The agent cites
    what retrieval actually returned, so the authoritative text stays in the retrieval layer (T16)
    instead of being copied into — and potentially altered by — a model's own output.

    A draft is never published automatically. docs/PRODUCT_AND_ARCHITECTURE.md requires a human to
    approve the exact proposed text first, which is the approval flow in T21/T22.
    """

    model_config = ConfigDict(extra="forbid")

    body: str = Field(max_length=4000)
    source_citations: list[str] = Field(default_factory=list)
