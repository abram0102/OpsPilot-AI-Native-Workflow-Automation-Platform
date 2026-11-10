"""Writing the run's trace and status.

The worker persists its own trace: docs/PRODUCT_AND_ARCHITECTURE.md's agent
execution step says it "executes allowlisted action through backend internal
API with idempotency key, **saves trace**, marks completed or failed". Only the
*action execution* goes through Java — and the internal API has no step-writing
endpoint, so there is nowhere else for a step to go.

Every statement is tenant-scoped, including the ones a run id alone would seem
to settle: a run id is never on its own sufficient to write anything.

What gets stored is sanitized by construction. These helpers take already-safe
summaries; they do not accept a provider response or a document body and trim it
down, because a redaction step that can be skipped eventually is.
"""

from __future__ import annotations

import json
from uuid import UUID

import psycopg

from opspilot_agent.graphs.state import TriageState

MAX_SANITIZED_CHARS = 2_000
"""A trace row is a summary. Anything longer is a payload, which belongs nowhere near here."""


def record_step(
    connection: psycopg.Connection,
    *,
    organization_id: UUID,
    run_id: UUID,
    sequence: int,
    step_type: str,
    status: str,
    sanitized_input: dict | None = None,
    sanitized_output: dict | None = None,
    duration_ms: int | None = None,
    model: str | None = None,
    tokens_in: int | None = None,
    tokens_out: int | None = None,
    error_code: str | None = None,
) -> None:
    """Upserts one step. Re-running a step overwrites its row rather than appending a second.

    The uniqueness is the database's: `UNIQUE (run_id, sequence)` from V004. Replay after a
    crash therefore converges on one row per position instead of accumulating duplicates.
    """
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO agent_steps (organization_id, run_id, sequence, step_type, status,
                                     sanitized_input, sanitized_output, duration_ms, model,
                                     tokens_in, tokens_out, error_code)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (run_id, sequence) DO UPDATE
               SET step_type        = EXCLUDED.step_type,
                   status           = EXCLUDED.status,
                   sanitized_input  = EXCLUDED.sanitized_input,
                   sanitized_output = EXCLUDED.sanitized_output,
                   duration_ms      = EXCLUDED.duration_ms,
                   model            = EXCLUDED.model,
                   tokens_in        = EXCLUDED.tokens_in,
                   tokens_out       = EXCLUDED.tokens_out,
                   error_code       = EXCLUDED.error_code
            """,
            (
                organization_id,
                run_id,
                sequence,
                step_type,
                status,
                _as_json(sanitized_input),
                _as_json(sanitized_output),
                duration_ms,
                model,
                tokens_in,
                tokens_out,
                error_code,
            ),
        )
    connection.commit()


def set_run_status(
    connection: psycopg.Connection,
    *,
    organization_id: UUID,
    run_id: UUID,
    status: str,
    error_code: str | None = None,
) -> None:
    """Moves the run's status.

    The database CHECK validates the *value*; it does not know about legal *transitions*. Java's
    `AgentRunService` holds that table, and this service does not duplicate it — the graph only
    ever drives a run forward along the one path it walks, and a second writer touching the same
    run is prevented by the lease rather than by a second copy of the rules (ADR-043).
    """
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE agent_runs
               SET status = %s, last_error_code = %s, updated_at = now()
             WHERE id = %s AND organization_id = %s
            """,
            (status, error_code, run_id, organization_id),
        )
    connection.commit()


def record_usage(
    connection: psycopg.Connection,
    *,
    organization_id: UUID,
    run_id: UUID,
    state: TriageState,
) -> None:
    """Writes the run's own counters from whatever the graph actually accumulated."""
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE agent_runs
               SET steps_used = %s,
                   total_input_tokens = %s,
                   total_output_tokens = %s,
                   checkpoint_thread_id = %s,
                   updated_at = now()
             WHERE id = %s AND organization_id = %s
            """,
            (
                state.get("steps_used", 0),
                state.get("input_tokens", 0),
                state.get("output_tokens", 0),
                # docs/DATA_MODEL.md: the checkpoint thread id *is* the run id, not a separate
                # identifier — so a checkpoint is always findable from the run and vice versa.
                run_id,
                run_id,
                organization_id,
            ),
        )
    connection.commit()


def _as_json(value: dict | None) -> str | None:
    if value is None:
        return None
    encoded = json.dumps(value, ensure_ascii=False)
    if len(encoded) > MAX_SANITIZED_CHARS:
        # Truncating here is a backstop. A caller handing this a payload that large has already
        # made a mistake; the row records that rather than silently storing it.
        return json.dumps({"truncated": True, "chars": len(encoded)})
    return encoded
