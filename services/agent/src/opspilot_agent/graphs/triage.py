"""The support-triage graph (docs/TASKS.md T19).

Nodes, in the order the ticket specifies: `load_ticket`, `classify`,
`retrieve_knowledge`, `draft_reply`, `propose_actions`, `policy_gate`,
`finalize`. It is a straight line with no cycles — docs/PRODUCT_AND_ARCHITECTURE
requires "no open-ended recursive loops", and the simplest way to guarantee that
is a graph that cannot loop at all.

**It executes nothing.** `propose_actions` produces typed proposals and
`policy_gate` classifies them; a risky write leaves the run `AWAITING_APPROVAL`
for a human. Execution needs T21's internal API (and, for a reply, an approval
id only T22 can mint), so a run here ends at a decision, never at an effect.

**Note on the node list.** docs/PRODUCT_AND_ARCHITECTURE.md gives a slightly
different sequence — no `draft_reply`, and a `safe_action`/`await_approval`
branch after the gate. This file follows T19's list, which is the ticket's; see
docs/DECISIONS.md ADR-043 for the discrepancy and why the branch is absent.

Untrusted text — the ticket body and retrieved chunks — reaches a prompt only as
quoted data under an explicit heading, never as instructions
(docs/SECURITY_AND_TESTING.md: "include retrieved content only as quoted
untrusted data, never let it override system/developer instructions").
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from uuid import UUID

import psycopg
from langgraph.graph import END, START, StateGraph

from opspilot_agent.config import Settings
from opspilot_agent.graphs.policy import GatedProposal, PolicyDecision, gate
from opspilot_agent.graphs.state import Citation, ProposalRecord, TriageState
from opspilot_agent.knowledge.retrieval import search_knowledge
from opspilot_agent.prompts import PromptName, load_prompt
from opspilot_agent.providers.protocols import EmbeddingProvider, LLMProvider
from opspilot_agent.providers.schemas import (
    AgentActionType,
    Classification,
    ReplyDraft,
    ToolProposal,
)
from opspilot_agent.providers.tokens import estimate_tokens

RETRIEVAL_LIMIT = 4


class RunLimitExceeded(Exception):
    """A bound was hit. The run ends FAILED with a code, rather than continuing past its budget."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class LimitCode:
    STEPS = "RUN_STEP_LIMIT_EXCEEDED"
    TIME = "RUN_TIME_LIMIT_EXCEEDED"
    TOKENS = "RUN_TOKEN_LIMIT_EXCEEDED"


@dataclass
class TriageContext:
    """Everything the nodes need that is not graph state.

    Passed in rather than imported so a test supplies its own providers and connection, and so
    nothing in a node reaches for global configuration mid-run.
    """

    settings: Settings
    connection: psycopg.Connection
    llm: LLMProvider
    embeddings: EmbeddingProvider
    started_at: float

    def check_bounds(self, state: TriageState) -> None:
        """Raises as soon as any budget is spent. Called at the top of every node."""
        if state.get("steps_used", 0) >= self.settings.agent_max_steps:
            raise RunLimitExceeded(
                LimitCode.STEPS,
                f"Run exceeded {self.settings.agent_max_steps} steps.",
            )
        elapsed = time.monotonic() - self.started_at
        if elapsed > self.settings.agent_max_run_seconds:
            raise RunLimitExceeded(
                LimitCode.TIME,
                f"Run exceeded {self.settings.agent_max_run_seconds}s.",
            )
        total_tokens = state.get("input_tokens", 0) + state.get("output_tokens", 0)
        budget = self.settings.agent_max_input_tokens + self.settings.agent_max_output_tokens
        if total_tokens > budget:
            raise RunLimitExceeded(LimitCode.TOKENS, f"Run exceeded its {budget}-token budget.")


def build_triage_graph(context: TriageContext):
    """Assembles the graph. Compiled by the caller, which supplies the checkpointer."""
    builder = StateGraph(TriageState)

    builder.add_node("load_ticket", _node(context, _load_ticket))
    builder.add_node("classify", _node(context, _classify))
    builder.add_node("retrieve_knowledge", _node(context, _retrieve_knowledge))
    builder.add_node("draft_reply", _node(context, _draft_reply))
    builder.add_node("propose_actions", _node(context, _propose_actions))
    builder.add_node("policy_gate", _node(context, _policy_gate))
    builder.add_node("finalize", _node(context, _finalize))

    builder.add_edge(START, "load_ticket")
    builder.add_edge("load_ticket", "classify")
    builder.add_edge("classify", "retrieve_knowledge")
    builder.add_edge("retrieve_knowledge", "draft_reply")
    builder.add_edge("draft_reply", "propose_actions")
    builder.add_edge("propose_actions", "policy_gate")
    builder.add_edge("policy_gate", "finalize")
    builder.add_edge("finalize", END)

    return builder


def _node(context: TriageContext, fn):
    """Wraps a node so every one checks its budget first and counts itself afterwards."""

    async def run(state: TriageState) -> dict:
        context.check_bounds(state)
        result = await fn(context, state)
        result["steps_used"] = state.get("steps_used", 0) + 1
        return result

    return run


async def _load_ticket(context: TriageContext, state: TriageState) -> dict:
    """Reads the ticket, tenant-scoped. The run's own organization is the only one it can see."""
    with context.connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT subject, description
              FROM tickets
             WHERE id = %s AND organization_id = %s
            """,
            (UUID(state["ticket_id"]), UUID(state["organization_id"])),
        )
        row = cursor.fetchone()

    if row is None:
        raise RunLimitExceeded("TICKET_NOT_FOUND", "The ticket no longer exists.")

    return {"ticket_subject": row[0], "ticket_description": row[1]}


async def _classify(context: TriageContext, state: TriageState) -> dict:
    messages = [
        {"role": "system", "content": load_prompt(PromptName.CLASSIFICATION_V1)},
        # The ticket is data. It is handed over under a heading that says so, and the system
        # prompt already instructs the model to treat anything inside it as content.
        {
            "role": "user",
            "content": (
                "Classify the following support ticket.\n\n"
                "--- BEGIN UNTRUSTED TICKET ---\n"
                f"Subject: {state['ticket_subject']}\n\n"
                f"{state['ticket_description']}\n"
                "--- END UNTRUSTED TICKET ---"
            ),
        },
    ]

    classification: Classification = await context.llm.structured_response(
        messages=messages,
        schema=Classification,
        max_output_tokens=context.settings.agent_max_output_tokens,
        timeout_s=context.settings.llm_timeout_seconds,
    )

    return {
        "category": classification.category.value,
        "urgency": classification.urgency.value,
        "classification_summary": classification.summary,
        "classification_confidence": classification.confidence,
        "input_tokens": state.get("input_tokens", 0) + _estimate(messages),
        "output_tokens": state.get("output_tokens", 0) + estimate_tokens(classification.summary),
    }


async def _retrieve_knowledge(context: TriageContext, state: TriageState) -> dict:
    """Searches this organization's READY documents only — the filter lives in the SQL (T16)."""
    chunks = await search_knowledge(
        context.connection,
        organization_id=UUID(state["organization_id"]),
        query=f"{state['ticket_subject']} {state.get('classification_summary', '')}",
        embeddings=context.embeddings,
        limit=RETRIEVAL_LIMIT,
    )

    citations: list[Citation] = [
        {
            "document_id": str(chunk.document_id),
            "chunk_index": chunk.chunk_index,
            "title": chunk.title,
            "score": round(chunk.score, 4),
        }
        for chunk in chunks
    ]

    # Each excerpt is fenced and labelled untrusted. The drafting prompt tells the model the same
    # thing; neither alone would be enough.
    retrieved_text = "\n\n".join(
        f"--- BEGIN UNTRUSTED EXCERPT [{chunk.document_id}#{chunk.chunk_index}] ---\n"
        f"{chunk.text}\n"
        f"--- END UNTRUSTED EXCERPT ---"
        for chunk in chunks
    )

    return {"citations": citations, "retrieved_text": retrieved_text}


async def _draft_reply(context: TriageContext, state: TriageState) -> dict:
    citation_ids = [
        f"{citation['document_id']}#{citation['chunk_index']}"
        for citation in state.get("citations", [])
    ]

    messages = [
        {"role": "system", "content": load_prompt(PromptName.REPLY_DRAFT_V1)},
        {
            "role": "user",
            "content": (
                "Draft a reply to this ticket using only the excerpts provided.\n\n"
                "--- BEGIN UNTRUSTED TICKET ---\n"
                f"Subject: {state['ticket_subject']}\n\n"
                f"{state['ticket_description']}\n"
                "--- END UNTRUSTED TICKET ---\n\n"
                f"{state.get('retrieved_text', '(no excerpts were found)')}\n\n"
                f"Citable excerpt identifiers: {', '.join(citation_ids) or '(none)'}"
            ),
        },
    ]

    draft: ReplyDraft = await context.llm.structured_response(
        messages=messages,
        schema=ReplyDraft,
        max_output_tokens=context.settings.agent_max_output_tokens,
        timeout_s=context.settings.llm_timeout_seconds,
    )

    return {
        "draft_body": draft.body,
        "input_tokens": state.get("input_tokens", 0) + _estimate(messages),
        "output_tokens": state.get("output_tokens", 0) + estimate_tokens(draft.body),
    }


async def _propose_actions(context: TriageContext, state: TriageState) -> dict:
    """Builds the proposals deterministically from what the graph already established.

    No model call: the actions follow from the classification and the fact that a draft exists,
    and asking a model to name its own tools would hand it exactly the choice AGENTS.md says it
    must not have ("AI cannot choose which permissions it has"). The gate would reject anything
    out of bounds regardless, but not offering the choice is the stronger position.
    """
    proposals = [
        ToolProposal(
            tool_call_id=f"{state['run_id']}:tag",
            action_type=AgentActionType.ADD_INTERNAL_TAG,
            arguments={"tag": state.get("category", "other").lower()},
            rationale=f"Classified as {state.get('category')} with "
            f"{state.get('classification_confidence')} confidence.",
        )
    ]

    if state.get("draft_body"):
        proposals.append(
            ToolProposal(
                tool_call_id=f"{state['run_id']}:reply",
                action_type=AgentActionType.PUBLISH_INTERNAL_REPLY,
                arguments={},
                rationale="A reply was drafted from retrieved knowledge and needs review.",
            )
        )

    return {"ungated_proposals": [p.model_dump(mode="json") for p in proposals]}


async def _policy_gate(context: TriageContext, state: TriageState) -> dict:
    """Runs every proposal past deterministic policy. Nothing reaches `finalize` ungated."""
    raw = state.get("ungated_proposals", [])
    proposals = [ToolProposal.model_validate(item) for item in raw]
    gated: list[GatedProposal] = gate(proposals)

    records: list[ProposalRecord] = [
        {
            "tool_call_id": item.proposal.tool_call_id,
            "action_type": item.proposal.action_type.value,
            "arguments": item.proposal.arguments,
            "rationale": item.proposal.rationale,
            "decision": item.decision.value,
            "rejection_code": item.rejection_code.value if item.rejection_code else None,
        }
        for item in gated
    ]

    approval_required = any(item.decision is PolicyDecision.REQUIRE_APPROVAL for item in gated)

    return {"proposals": records, "approval_required": approval_required}


async def _finalize(context: TriageContext, state: TriageState) -> dict:
    """Decides the run's terminal state.

    A run that proposed a risky write stops at `AWAITING_APPROVAL` — not completed, because the
    thing it set out to do has not happened and a human still has to decide.
    """
    terminal = "AWAITING_APPROVAL" if state.get("approval_required") else "COMPLETED"
    return {"terminal_status": terminal, "error_code": None}


def _estimate(messages: list[dict[str, str]]) -> int:
    return sum(estimate_tokens(message.get("content", "")) for message in messages)
