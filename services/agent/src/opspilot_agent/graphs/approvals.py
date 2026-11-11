"""Persisting the approval a paused run is waiting on (docs/TASKS.md T23).

The row and the checkpoint are two writes to the same database that cannot be
made one transaction: the checkpointer owns its own connection and commits on its
own schedule. The crash window that creates, and the ordering chosen to make it
recoverable, are documented in docs/DECISIONS.md ADR-047 — this module is the
half we control.

The insert is idempotent on `(run_id, tool_call_id)` because LangGraph re-runs
an interrupted node from its start, so anything upstream of a retry may execute
twice. Crucially it does **not** touch `status` on conflict: a resume that
re-ran this would otherwise reset a decided approval back to `PENDING` and
re-open a gate a human has already closed.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from enum import StrEnum
from uuid import UUID

import psycopg
from pydantic import BaseModel, ConfigDict, Field


class ApprovalStatus(StrEnum):
    """Mirrors the database CHECK and services/api's enum."""

    PENDING = "PENDING"
    APPROVED = "APPROVED"
    REJECTED = "REJECTED"
    EXPIRED = "EXPIRED"


class ResumeDecision(BaseModel):
    """The shape a resume value must take.

    Passed to `interrupt(..., response_schema=...)`, which validates against it and — per
    LangGraph's own docstring — saves nothing when the value is invalid, so the interrupt can be
    answered again. That is what makes "malformed resume rejected" a property of the graph rather
    than a check we remembered to write.

    It is still only a *claim*: the decision is re-read from the database before anything acts on
    it, exactly as the API treats the worker's own claims (ADR-045).
    """

    model_config = ConfigDict(extra="forbid")

    approval_id: UUID
    decision: ApprovalStatus = Field(
        description="APPROVED or REJECTED; the other states are not decisions a human makes here."
    )


@dataclass(frozen=True)
class StoredApproval:
    id: UUID
    run_id: UUID
    ticket_id: UUID
    tool_call_id: str
    status: ApprovalStatus
    proposal: dict


def persist_pending_approval(
    connection: psycopg.Connection,
    *,
    organization_id: UUID,
    run_id: UUID,
    ticket_id: UUID,
    tool_call_id: str,
    action_type: str,
    proposal: dict,
) -> UUID:
    """Records the proposal awaiting a human, returning its id (existing or new).

    `decision_by`/`decision_at` are left null because the table's own
    `ck_approvals_decision_consistent` requires a PENDING row to have neither.
    """
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO approvals (organization_id, run_id, ticket_id, tool_call_id,
                                   action_type, proposal, status)
            VALUES (%s, %s, %s, %s, %s, %s, 'PENDING')
            ON CONFLICT (run_id, tool_call_id) DO UPDATE
               -- A no-op touch purely so RETURNING yields the existing row's id. Updating
               -- `status` here would re-open a gate a human has already closed.
               SET updated_at = approvals.updated_at
            RETURNING id
            """,
            (
                organization_id,
                run_id,
                ticket_id,
                tool_call_id,
                action_type,
                json.dumps(proposal, ensure_ascii=False),
            ),
        )
        approval_id = cursor.fetchone()[0]
    connection.commit()
    return approval_id


def read_approval(
    connection: psycopg.Connection, *, organization_id: UUID, approval_id: UUID
) -> StoredApproval | None:
    """Re-reads an approval. The authority on what was proposed and what was decided."""
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT id, run_id, ticket_id, tool_call_id, status, proposal
              FROM approvals
             WHERE id = %s AND organization_id = %s
            """,
            (approval_id, organization_id),
        )
        row = cursor.fetchone()

    if row is None:
        return None

    proposal = row[5]
    return StoredApproval(
        id=row[0],
        run_id=row[1],
        ticket_id=row[2],
        tool_call_id=row[3],
        status=ApprovalStatus(row[4]),
        proposal=proposal if isinstance(proposal, dict) else json.loads(proposal),
    )
