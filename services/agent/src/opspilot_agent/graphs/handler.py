"""Running the triage graph, and a CLI that drives it directly.

T19 asks for a "direct synchronous test harness **outside HTTP request thread**".
That constraint is met by construction here: this service's FastAPI app exposes
health only, and nothing in this module touches it. A run is executed by calling
`execute_run`, never inside a web request — so a slow provider can never occupy
a request thread, and the same function is what T25's Redis consumer will call.

The entry point takes the event payload T18's outbox row already carries
(`{runId, organizationId, ticketId, workflow}`), so wiring a consumer to it later
reshapes nothing.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from dataclasses import dataclass
from uuid import UUID

from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from langgraph.types import Command

from opspilot_agent.clients.internal_actions import InternalActionClient, InternalActionError
from opspilot_agent.config import Settings, get_settings
from opspilot_agent.db import build_dsn, connect
from opspilot_agent.graphs.state import TriageState
from opspilot_agent.graphs.trace import record_step, record_usage, set_run_status
from opspilot_agent.graphs.triage import RunLimitExceeded, TriageContext, build_triage_graph
from opspilot_agent.providers.errors import AiProviderError
from opspilot_agent.providers.factory import build_embedding_provider, build_llm_provider

# The step row each node writes, in graph order. Kept beside the graph's own order deliberately:
# a node added without a sequence here would silently overwrite another node's row.
STEP_SEQUENCE: dict[str, tuple[int, str]] = {
    "load_ticket": (0, "CLASSIFY"),
    "classify": (1, "CLASSIFY"),
    "retrieve_knowledge": (2, "RETRIEVE"),
    "draft_reply": (3, "DRAFT_REPLY"),
    "propose_actions": (4, "PROPOSE_TOOL"),
    "policy_gate": (5, "PROPOSE_TOOL"),
    "persist_approval": (6, "AWAIT_APPROVAL"),
    "await_approval": (7, "AWAIT_APPROVAL"),
    "execute_approved_action": (8, "EXECUTE_TOOL"),
    "finalize": (9, "FINALIZE"),
}

INTERRUPT_KEY = "__interrupt__"
"""LangGraph signals a pause by putting this key in the streamed update, with a *tuple* of
`Interrupt` as its value rather than node state. Treating it as a node would both mis-sequence a
step row and crash on the tuple, so the stream loop checks for it explicitly."""


@dataclass(frozen=True)
class RunOutcome:
    run_id: UUID
    status: str
    approval_required: bool
    error_code: str | None = None
    published_reference: str | None = None

    @property
    def succeeded(self) -> bool:
        return self.status in ("COMPLETED", "AWAITING_APPROVAL")


async def execute_run(
    *,
    organization_id: UUID,
    run_id: UUID,
    ticket_id: UUID,
    settings: Settings | None = None,
    internal_actions: InternalActionClient | None = None,
) -> RunOutcome:
    """Starts a run, persisting its trace as it goes.

    Idempotent enough to be re-delivered: step rows upsert on `(run_id, sequence)`, and the
    checkpointer keys on the run id, so a replay resumes rather than duplicating.
    """
    initial: TriageState = {
        "organization_id": str(organization_id),
        "run_id": str(run_id),
        "ticket_id": str(ticket_id),
        "steps_used": 0,
        "input_tokens": 0,
        "output_tokens": 0,
    }
    return await _drive(
        organization_id=organization_id,
        run_id=run_id,
        graph_input=initial,
        settings=settings,
        internal_actions=internal_actions,
    )


async def resume_run(
    *,
    organization_id: UUID,
    run_id: UUID,
    approval_id: UUID,
    decision: str,
    settings: Settings | None = None,
    internal_actions: InternalActionClient | None = None,
) -> RunOutcome:
    """Continues a paused run once a human has decided (docs/TASKS.md T23).

    The decision passed here is only what the caller *claims*; `execute_approved_action` re-reads
    the approval from the database and acts on that. A malformed value is refused by LangGraph
    itself, which validates the resume against `ResumeDecision` and saves nothing when it fails —
    so the pause survives a bad resume and can be answered again.

    Resuming a run that is not paused is a no-op rather than an error: at-least-once delivery
    means this will be called twice for the same decision, and the second call finds no pending
    interrupt and simply reports the run's current state.
    """
    return await _drive(
        organization_id=organization_id,
        run_id=run_id,
        graph_input=Command(resume={"approval_id": str(approval_id), "decision": decision}),
        settings=settings,
        internal_actions=internal_actions,
    )


async def _drive(
    *,
    organization_id: UUID,
    run_id: UUID,
    graph_input: object,
    settings: Settings | None = None,
    internal_actions: InternalActionClient | None = None,
) -> RunOutcome:
    """Runs the graph forward from wherever it is, start or resume.

    One driver for both so a resumed run records its trace, its usage and its status exactly the
    way a fresh one does — two code paths would eventually disagree about one of them.
    """
    settings = settings or get_settings()

    async with AsyncPostgresSaver.from_conn_string(build_dsn(settings)) as checkpointer:
        # The checkpointer owns its own tables and creates them itself — docs/DATA_MODEL.md
        # exempts them from Flyway explicitly ("initialized by the installed checkpointer
        # package"). Calling setup() repeatedly is safe.
        await checkpointer.setup()

        with connect(settings) as connection:
            context = TriageContext(
                settings=settings,
                connection=connection,
                llm=build_llm_provider(settings),
                embeddings=build_embedding_provider(settings),
                started_at=time.monotonic(),
                internal_actions=internal_actions or InternalActionClient(settings),
            )
            graph = build_triage_graph(context).compile(checkpointer=checkpointer)

            set_run_status(
                connection, organization_id=organization_id, run_id=run_id, status="RUNNING"
            )

            # docs/DATA_MODEL.md: "stable thread_id=agent_runs.id". The checkpoint is therefore
            # always findable from the run, which is what makes resume possible across processes.
            config = {"configurable": {"thread_id": str(run_id)}}

            final_state: TriageState = {}
            paused = False
            try:
                async for update in graph.astream(graph_input, config=config):
                    if INTERRUPT_KEY in update:
                        # The run is parked on a human. `persist_approval` has already written the
                        # approval row and set AWAITING_APPROVAL, so there is nothing more to do
                        # here but stop — and specifically not to mark the run finished.
                        paused = True
                        continue
                    for node_name, node_state in update.items():
                        final_state = {**final_state, **node_state}
                        _persist_step(connection, organization_id, run_id, node_name, node_state)
            except RunLimitExceeded as limit:
                return _fail(connection, organization_id, run_id, limit.code, final_state)
            except AiProviderError as error:
                # Already bounded by T15's retry budget and breaker; by the time it surfaces the
                # attempt is over. The run stays retryable from the outside.
                return _fail(connection, organization_id, run_id, str(error.code), final_state)
            except InternalActionError:
                # The action could not be attempted. Nothing was published, and the run is left
                # FAILED with a code rather than silently COMPLETED.
                return _fail(
                    connection, organization_id, run_id, "INTERNAL_ACTION_UNAVAILABLE", final_state
                )

            record_usage(
                connection, organization_id=organization_id, run_id=run_id, state=final_state
            )

            if paused:
                # Deliberately not a terminal state: the run continues when a decision arrives.
                set_run_status(
                    connection,
                    organization_id=organization_id,
                    run_id=run_id,
                    status="AWAITING_APPROVAL",
                )
                return RunOutcome(run_id=run_id, status="AWAITING_APPROVAL", approval_required=True)

            status = final_state.get("terminal_status", "COMPLETED")
            set_run_status(
                connection, organization_id=organization_id, run_id=run_id, status=status
            )

            return RunOutcome(
                run_id=run_id,
                status=status,
                approval_required=bool(final_state.get("approval_required")),
                published_reference=final_state.get("published_reference"),
            )


def _persist_step(connection, organization_id: UUID, run_id: UUID, node_name: str, node_state):
    sequence, step_type = STEP_SEQUENCE.get(node_name, (99, "FINALIZE"))
    record_step(
        connection,
        organization_id=organization_id,
        run_id=run_id,
        sequence=sequence,
        step_type=step_type,
        status="SUCCEEDED",
        sanitized_output=_sanitized_output(node_name, node_state),
    )


def _sanitized_output(node_name: str, node_state) -> dict | None:
    """What a step row is allowed to remember about a node.

    Summaries and identifiers only. Notably absent: the ticket body, retrieved excerpt text and
    the drafted reply — docs/SECURITY_AND_TESTING.md asks for sanitized summaries, and a trace
    that quoted a prompt-injection payload back would be a way to smuggle it onward.
    """
    if node_name == "classify":
        return {
            "category": node_state.get("category"),
            "urgency": node_state.get("urgency"),
            "confidence": node_state.get("classification_confidence"),
        }
    if node_name == "retrieve_knowledge":
        return {"citations": node_state.get("citations", [])}
    if node_name == "draft_reply":
        return {"draft_chars": len(node_state.get("draft_body", ""))}
    if node_name == "policy_gate":
        return {
            "decisions": [
                {
                    "tool_call_id": record["tool_call_id"],
                    "action_type": record["action_type"],
                    "decision": record["decision"],
                    "rejection_code": record["rejection_code"],
                }
                for record in node_state.get("proposals", [])
            ]
        }
    if node_name == "finalize":
        return {"terminal_status": node_state.get("terminal_status")}
    return None


def _fail(
    connection, organization_id: UUID, run_id: UUID, code: str, state: TriageState
) -> RunOutcome:
    record_usage(connection, organization_id=organization_id, run_id=run_id, state=state)
    set_run_status(
        connection,
        organization_id=organization_id,
        run_id=run_id,
        status="FAILED",
        error_code=code,
    )
    return RunOutcome(run_id=run_id, status="FAILED", approval_required=False, error_code=code)


async def handle_run_event(payload: dict, settings: Settings | None = None) -> RunOutcome:
    """Executes the run an `agent.run.requested` event names (T18's outbox payload shape)."""
    try:
        organization_id = UUID(str(payload["organizationId"]))
        run_id = UUID(str(payload["runId"]))
        ticket_id = UUID(str(payload["ticketId"]))
    except (KeyError, ValueError) as error:
        raise ValueError(
            "Run event payload needs `runId`, `organizationId` and `ticketId` UUIDs."
        ) from error

    return await execute_run(
        organization_id=organization_id,
        run_id=run_id,
        ticket_id=ticket_id,
        settings=settings,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m opspilot_agent.graphs.handler",
        description="Execute one agent run (stand-in for the T25 Redis consumer).",
    )
    parser.add_argument("--organization-id", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--ticket-id", required=True)
    args = parser.parse_args(argv)

    outcome = asyncio.run(
        handle_run_event(
            {
                "organizationId": args.organization_id,
                "runId": args.run_id,
                "ticketId": args.ticket_id,
            }
        )
    )

    print(
        json.dumps(
            {
                "runId": str(outcome.run_id),
                "status": outcome.status,
                "approvalRequired": outcome.approval_required,
                "errorCode": outcome.error_code,
            }
        )
    )
    return 0 if outcome.succeeded else 1


if __name__ == "__main__":
    sys.exit(main())
