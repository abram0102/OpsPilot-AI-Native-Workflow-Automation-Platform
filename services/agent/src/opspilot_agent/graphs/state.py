"""The graph's typed state.

A `TypedDict` rather than a free-form dict so a node cannot invent a key and a
reader cannot guess at one. LangGraph merges each node's returned partial into
this shape, which is also what gets checkpointed — so every field here must be
JSON-serialisable, and nothing secret may live in it.

Deliberately absent: raw provider responses, API keys, and the full text of
retrieved documents beyond the citations the reply actually uses. The state is
persisted, and docs/SECURITY_AND_TESTING.md asks for sanitized summaries rather
than raw payloads.
"""

from __future__ import annotations

from typing import Annotated, TypedDict


class Citation(TypedDict):
    """A chunk the draft actually relied on."""

    document_id: str
    chunk_index: int
    title: str
    score: float


class ProposalRecord(TypedDict):
    """A proposal plus what the policy gate decided about it."""

    tool_call_id: str
    action_type: str
    arguments: dict[str, str]
    rationale: str
    decision: str
    rejection_code: str | None


class TriageState(TypedDict, total=False):
    """Everything the triage graph reads and writes.

    `total=False` because nodes fill it progressively; a node must tolerate the keys written
    before it and nothing else.
    """

    # Identity, set before the graph starts and never changed by a node.
    organization_id: str
    run_id: str
    ticket_id: str

    # load_ticket
    ticket_subject: str
    ticket_description: str

    # classify
    category: str
    urgency: str
    classification_summary: str
    classification_confidence: float

    # retrieve_knowledge
    citations: list[Citation]
    retrieved_text: Annotated[str, "Quoted untrusted knowledge, passed to the drafting prompt"]

    # draft_reply
    draft_body: str

    # propose_actions hands these to policy_gate. They are the *ungated* proposals, so nothing
    # outside the gate should read them — hence a name that says so.
    ungated_proposals: list[dict]

    # policy_gate
    proposals: list[ProposalRecord]
    approval_required: bool

    # persist_approval / await_approval / execute_approved_action
    approval_id: str
    approval_tool_call_id: str
    resume_decision: str
    resume_approval_id: str
    approval_outcome: str
    published_reference: str | None
    already_applied: bool

    # finalize
    terminal_status: str
    error_code: str | None

    # Bookkeeping the bounds are enforced against.
    steps_used: int
    input_tokens: int
    output_tokens: int
