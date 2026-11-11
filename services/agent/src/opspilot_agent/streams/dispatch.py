"""Turning one envelope into one unit of work.

The envelope carries identifiers and nothing else, so this module's first job on every event is
to **read the real state back out of Postgres** (docs/API_CONTRACT.md: "Published event payload
only contains identifiers; workers reload trusted DB state"). The outbox row in Postgres does
carry a richer payload, but that payload never reaches the wire and is deliberately not consulted
here: trusting a field that travelled through a queue would be trusting a copy of the truth
instead of the truth.

Two of the three event types name a *run*, and for those the current run status is what decides
whether anything happens at all. That check is what makes "replay of a completed run results in
zero duplicate side effect" true at this layer, before any graph is started.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from uuid import UUID

import psycopg

from opspilot_agent.clients.internal_actions import InternalActionClient
from opspilot_agent.config import Settings
from opspilot_agent.db import connect
from opspilot_agent.graphs.handler import execute_run, resume_run
from opspilot_agent.knowledge.handler import handle_ingest_event
from opspilot_agent.knowledge.indexer import DocumentNotFoundError
from opspilot_agent.streams.envelope import (
    AGENT_RUN_REQUESTED,
    AGENT_RUN_RESUME,
    KNOWLEDGE_INGEST_REQUESTED,
    Envelope,
)
from opspilot_agent.streams.leases import (
    acquire_run_lease,
    lease_heartbeat,
    release_run_lease,
)

log = logging.getLogger(__name__)

TERMINAL_RUN_STATUSES = frozenset({"COMPLETED", "FAILED", "CANCELLED"})
"""Statuses from which no event can move a run. V004's CHECK constraint is the full list."""

DECIDED_APPROVAL_STATUSES = ("APPROVED", "REJECTED")


class EventRejected(Exception):
    """The event can never be processed — dead-letter it rather than retrying.

    Carries a code, never a message built from data. The code vocabulary is small on purpose:
    these strings reach the dead-letter stream, which operators read.
    """

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class LeaseUnavailable(Exception):
    """Another worker holds the run's lease.

    Explicitly *not* a failure: it means the work is already in hand somewhere else. The caller
    must leave the message unacknowledged so it can be reclaimed if that other worker dies — and
    must not mark the run failed, because nothing failed.
    """


@dataclass(frozen=True)
class DispatchResult:
    event_id: UUID
    type: str
    executed: bool
    """False when the event was correctly ignored (a replay of finished work)."""
    status: str | None = None
    detail: str | None = None


async def dispatch(
    envelope: Envelope,
    *,
    settings: Settings,
    owner: str,
    internal_actions: InternalActionClient | None = None,
) -> DispatchResult:
    """Runs the handler this envelope names. Raises only for conditions the caller must act on.

    `internal_actions` is injected rather than constructed here for the same reason
    `execute_run`/`resume_run` take it: the worker's calls into the Java action API are a
    collaborator, and a test needs to be able to see what was sent without a live API. Left unset,
    each handler builds the real client from settings.
    """
    if envelope.type == KNOWLEDGE_INGEST_REQUESTED:
        return await _ingest(envelope, settings=settings)
    if envelope.type == AGENT_RUN_REQUESTED:
        return await _start_run(
            envelope, settings=settings, owner=owner, internal_actions=internal_actions
        )
    if envelope.type == AGENT_RUN_RESUME:
        return await _resume_run(
            envelope, settings=settings, owner=owner, internal_actions=internal_actions
        )
    # Unreachable: `parse_envelope` only returns ACCEPTED for the three types above. Kept as a
    # loud failure rather than a silent fall-through in case a fourth is added to one place only.
    raise EventRejected("UNHANDLED_EVENT_TYPE")


async def _ingest(envelope: Envelope, *, settings: Settings) -> DispatchResult:
    """`aggregateId` is the document id (the Java emitter's aggregate is `knowledge_document`).

    No lease: a document is not a run, and the indexer is already idempotent on
    `(document_id, chunk_index)` with its own status transitions (T16). A second delivery
    converges on the same chunks instead of duplicating them.
    """
    try:
        outcome = await handle_ingest_event(
            {
                "documentId": str(envelope.aggregate_id),
                "organizationId": str(envelope.organization_id),
            },
            settings,
        )
    except DocumentNotFoundError as error:
        # Deleted, or never in this tenant. No retry can conjure it back.
        raise EventRejected("DOCUMENT_NOT_FOUND") from error

    return DispatchResult(
        event_id=envelope.event_id,
        type=envelope.type,
        executed=True,
        status=outcome.status.value,
        # The indexer's own code, already sanitized by T16 — a classification, not a message.
        detail=outcome.error_code,
    )


async def _start_run(
    envelope: Envelope,
    *,
    settings: Settings,
    owner: str,
    internal_actions: InternalActionClient | None = None,
) -> DispatchResult:
    """`aggregateId` is the run id; the ticket id is read from the run row, not from the wire."""
    with connect(settings) as connection:
        run = _load_run(connection, envelope)

        if run.status in TERMINAL_RUN_STATUSES:
            # The acceptance criterion in one branch: a replayed start for a finished run does
            # nothing at all, so there is no second side effect to be idempotent about.
            return _skipped(envelope, run.status, "RUN_ALREADY_TERMINAL")
        if run.status == "AWAITING_APPROVAL":
            # A paused run must not be restarted from the top by a redelivered start event; it
            # continues only via agent.run.resume.
            return _skipped(envelope, run.status, "RUN_AWAITING_APPROVAL")

        if (
            acquire_run_lease(
                connection,
                organization_id=envelope.organization_id,
                run_id=run.id,
                owner=owner,
                ttl_seconds=settings.worker_lease_seconds,
            )
            is None
        ):
            raise LeaseUnavailable(f"run {run.id} is leased by another worker")

    try:
        async with lease_heartbeat(
            settings,
            organization_id=envelope.organization_id,
            run_id=run.id,
            owner=owner,
        ) as lease_lost:
            outcome = await execute_run(
                organization_id=envelope.organization_id,
                run_id=run.id,
                ticket_id=run.ticket_id,
                settings=settings,
                internal_actions=internal_actions,
            )
        _refuse_to_claim_work_we_no_longer_own(lease_lost, run.id)
    finally:
        _release(settings, envelope.organization_id, run.id, owner)

    return DispatchResult(
        event_id=envelope.event_id,
        type=envelope.type,
        executed=True,
        status=outcome.status,
        detail=outcome.error_code,
    )


async def _resume_run(
    envelope: Envelope,
    *,
    settings: Settings,
    owner: str,
    internal_actions: InternalActionClient | None = None,
) -> DispatchResult:
    """`aggregateId` is the run id. Which approval was decided is read from the database.

    The envelope cannot say: it carries one aggregate id and that is the run. So the decided
    approval is looked up here — and even then it is only a *claim* passed to the graph, which
    re-reads the approval itself before acting (T23, ADR-047). Nothing an event says is trusted
    as authorization.
    """
    with connect(settings) as connection:
        run = _load_run(connection, envelope)

        if run.status != "AWAITING_APPROVAL":
            # Already resumed by an earlier delivery, or finished. Either way there is no pending
            # interrupt for this to answer.
            return _skipped(envelope, run.status, "RUN_NOT_AWAITING_APPROVAL")

        approval = _load_decided_approval(connection, envelope)

        if (
            acquire_run_lease(
                connection,
                organization_id=envelope.organization_id,
                run_id=run.id,
                owner=owner,
                ttl_seconds=settings.worker_lease_seconds,
            )
            is None
        ):
            raise LeaseUnavailable(f"run {run.id} is leased by another worker")

    try:
        async with lease_heartbeat(
            settings,
            organization_id=envelope.organization_id,
            run_id=run.id,
            owner=owner,
        ) as lease_lost:
            outcome = await resume_run(
                organization_id=envelope.organization_id,
                run_id=run.id,
                approval_id=approval.id,
                decision=approval.status,
                settings=settings,
                internal_actions=internal_actions,
            )
        _refuse_to_claim_work_we_no_longer_own(lease_lost, run.id)
    finally:
        _release(settings, envelope.organization_id, run.id, owner)

    return DispatchResult(
        event_id=envelope.event_id,
        type=envelope.type,
        executed=True,
        status=outcome.status,
        detail=outcome.error_code,
    )


@dataclass(frozen=True)
class _RunRow:
    id: UUID
    ticket_id: UUID
    status: str


@dataclass(frozen=True)
class _ApprovalRow:
    id: UUID
    status: str


def _load_run(connection: psycopg.Connection, envelope: Envelope) -> _RunRow:
    """Reads the run, scoped to the tenant the envelope names.

    `organization_id` is in the WHERE clause, not merely checked afterwards: a run id from
    another tenant must look exactly like a run id that does not exist.
    """
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT id, ticket_id, status
              FROM agent_runs
             WHERE id = %s AND organization_id = %s
            """,
            (envelope.aggregate_id, envelope.organization_id),
        )
        row = cursor.fetchone()

    if row is None:
        # The outbox row is written in the same transaction as the run, so an event whose run is
        # missing means the run was deleted afterwards. Not retryable.
        raise EventRejected("RUN_NOT_FOUND")
    return _RunRow(id=row[0], ticket_id=row[1], status=row[2])


def _load_decided_approval(connection: psycopg.Connection, envelope: Envelope) -> _ApprovalRow:
    """The most recently decided approval on this run.

    One row today — the triage graph has a single `persist_approval` node, and
    `uq_approvals_run_tool_call` makes one approval per tool call — so "most recent" is
    unambiguous in practice. Ordered anyway rather than asserting a single row, because a graph
    with two gated actions would otherwise start failing here instead of resuming.
    """
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT id, status
              FROM approvals
             WHERE run_id = %s
               AND organization_id = %s
               AND status = ANY(%s)
             ORDER BY decision_at DESC
             LIMIT 1
            """,
            (envelope.aggregate_id, envelope.organization_id, list(DECIDED_APPROVAL_STATUSES)),
        )
        row = cursor.fetchone()

    if row is None:
        # The resume event and the decision are written in one transaction (T22's
        # ApprovalDecisionService), so this cannot happen from a race — only from the approval
        # having been removed.
        raise EventRejected("APPROVAL_NOT_DECIDED")
    return _ApprovalRow(id=row[0], status=row[1])


def _skipped(envelope: Envelope, status: str, detail: str) -> DispatchResult:
    log.info(
        "Ignoring %s for %s: %s (status %s).", envelope.type, envelope.aggregate_id, detail, status
    )
    return DispatchResult(
        event_id=envelope.event_id,
        type=envelope.type,
        executed=False,
        status=status,
        detail=detail,
    )


def _refuse_to_claim_work_we_no_longer_own(lease_lost, run_id: UUID) -> None:
    """Raises if the lease was taken away while the handler ran.

    The handler cannot be interrupted safely mid-node, so it is allowed to finish — but its
    result must not be reported as this worker's success, and the message must not be
    acknowledged on the strength of it. Another worker now owns the run and will acknowledge its
    own delivery.
    """
    if lease_lost.is_set():
        raise LeaseUnavailable(f"lease on run {run_id} was reclaimed mid-run")


def _release(settings: Settings, organization_id: UUID, run_id: UUID, owner: str) -> None:
    """Best-effort release, so a retry need not wait out the TTL.

    Swallows database errors deliberately and narrowly: the lease expires on its own, so failing
    to release it delays a retry rather than losing anything, and raising here would replace the
    handler's real outcome with a cleanup error.
    """
    try:
        with connect(settings) as connection:
            release_run_lease(
                connection, organization_id=organization_id, run_id=run_id, owner=owner
            )
    except psycopg.Error:
        log.warning("Could not release the lease on run %s; it will expire on its own.", run_id)
