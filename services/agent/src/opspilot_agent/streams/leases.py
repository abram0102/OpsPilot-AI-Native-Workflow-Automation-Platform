"""Per-run leases, held in Postgres rather than in Redis.

Two independent things have to be true for a worker to own a run, and they are kept in different
places on purpose:

* the *message* is pending for this consumer in the Redis group (Redis knows that), and
* the *run* is leased to this worker in `agent_runs` (Postgres knows that).

The lease is the authoritative one. Redis is transport: a reclaimed message can be handed to a
second worker while the first is still alive but wedged, and nothing in Redis would stop both
from running the same graph. The lease does, because taking it is a single conditional UPDATE.
`docs/AGENTS.md` is explicit that idempotency and mutual exclusion belong in database constraints
and row locks, "not in-memory mutexes" — a Redis lock would be neither.

The columns are `agent_runs.lease_owner` / `lease_expires_at`, created by V004 together with
`idx_agent_runs_claimable (status, lease_expires_at)`, so this ticket needs no migration.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import os
import socket
from collections.abc import AsyncIterator
from dataclasses import dataclass
from datetime import datetime
from uuid import UUID

import psycopg

from opspilot_agent.config import Settings
from opspilot_agent.db import build_dsn

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class Lease:
    run_id: UUID
    owner: str
    expires_at: datetime


def default_owner_name(settings: Settings) -> str:
    """This process's identity, used as both consumer name and lease owner.

    Host and pid, so two workers on one machine are distinguishable with no configuration. The
    same string is used for the Redis consumer name deliberately: when a dead-letter record or a
    stranded lease names an owner, it should be possible to tell which process that was without
    correlating two different naming schemes.
    """
    if settings.redis_consumer_name:
        return settings.redis_consumer_name
    return f"{socket.gethostname()}:{os.getpid()}"


def acquire_run_lease(
    connection: psycopg.Connection,
    *,
    organization_id: UUID,
    run_id: UUID,
    owner: str,
    ttl_seconds: int,
) -> Lease | None:
    """Takes the lease if it is free, expired, or already ours; None if someone else holds it.

    Re-taking our own lease is a success rather than a conflict: a redelivery to the same worker
    is normal under at-least-once, and failing it would strand the run until the lease expired.
    """
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE agent_runs
               SET lease_owner = %(owner)s,
                   lease_expires_at = now() + make_interval(secs => %(ttl)s),
                   updated_at = now()
             WHERE id = %(run_id)s
               AND organization_id = %(organization_id)s
               AND (
                     lease_owner IS NULL
                  OR lease_owner = %(owner)s
                  -- A lease with no expiry could never be reclaimed, which would strand the run
                  -- permanently. Treated as stealable rather than sacred.
                  OR lease_expires_at IS NULL
                  OR lease_expires_at <= now()
               )
         RETURNING lease_owner, lease_expires_at
            """,
            {
                "owner": owner,
                "ttl": ttl_seconds,
                "run_id": run_id,
                "organization_id": organization_id,
            },
        )
        row = cursor.fetchone()
    connection.commit()

    if row is None:
        # Either another worker holds an unexpired lease, or the run does not exist in this
        # tenant. Both are "not ours to run", and the caller must not guess which.
        return None
    return Lease(run_id=run_id, owner=row[0], expires_at=row[1])


def renew_run_lease(
    connection: psycopg.Connection,
    *,
    organization_id: UUID,
    run_id: UUID,
    owner: str,
    ttl_seconds: int,
) -> bool:
    """Pushes our own lease out. Fails if we no longer hold it.

    The failure case is the interesting one: it means another worker already declared this run
    abandoned and took over. A heartbeat that silently re-took the lease would produce exactly
    the double execution the lease exists to prevent, so the owner is part of the WHERE clause.
    """
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE agent_runs
               SET lease_expires_at = now() + make_interval(secs => %(ttl)s),
                   updated_at = now()
             WHERE id = %(run_id)s
               AND organization_id = %(organization_id)s
               AND lease_owner = %(owner)s
            """,
            {
                "ttl": ttl_seconds,
                "run_id": run_id,
                "organization_id": organization_id,
                "owner": owner,
            },
        )
        renewed = cursor.rowcount == 1
    connection.commit()
    return renewed


def release_run_lease(
    connection: psycopg.Connection,
    *,
    organization_id: UUID,
    run_id: UUID,
    owner: str,
) -> bool:
    """Gives the lease back on a clean finish, so a retry need not wait out the TTL.

    Only ever releases our own: a worker that lost the lease mid-run must not clear the lease the
    new owner is relying on.
    """
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE agent_runs
               SET lease_owner = NULL,
                   lease_expires_at = NULL,
                   updated_at = now()
             WHERE id = %(run_id)s
               AND organization_id = %(organization_id)s
               AND lease_owner = %(owner)s
            """,
            {"run_id": run_id, "organization_id": organization_id, "owner": owner},
        )
        released = cursor.rowcount == 1
    connection.commit()
    return released


@contextlib.asynccontextmanager
async def lease_heartbeat(
    settings: Settings,
    *,
    organization_id: UUID,
    run_id: UUID,
    owner: str,
) -> AsyncIterator[asyncio.Event]:
    """Keeps the lease alive for as long as the body runs.

    Yields an event that is **set if the lease is ever lost**. The handler is not killed — there
    is no safe way to interrupt a graph mid-node — but the consumer checks the event before
    acknowledging, so a worker that was declared dead does not then report success for a run
    another worker has taken over.

    Uses its own connection, not the handler's: psycopg connections are not safe to use from two
    places at once, and a heartbeat that waited behind a long-running statement would defeat the
    point.
    """
    lost = asyncio.Event()

    async def beat() -> None:
        while True:
            await asyncio.sleep(settings.worker_heartbeat_seconds)
            try:
                renewed = await asyncio.to_thread(
                    _renew_on_a_fresh_connection,
                    settings,
                    organization_id=organization_id,
                    run_id=run_id,
                    owner=owner,
                )
            except psycopg.Error:
                # A database blip is not proof the lease was taken. Try again next beat; the TTL
                # is several heartbeats long precisely so one failure is survivable.
                log.warning("Lease heartbeat for run %s could not reach the database.", run_id)
                continue
            if not renewed:
                log.error(
                    "Lost the lease on run %s — another worker has taken it over. This run's "
                    "result will not be acknowledged by this worker.",
                    run_id,
                )
                lost.set()
                return

    task = asyncio.create_task(beat())
    try:
        yield lost
    finally:
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task


def _renew_on_a_fresh_connection(
    settings: Settings, *, organization_id: UUID, run_id: UUID, owner: str
) -> bool:
    with psycopg.connect(build_dsn(settings)) as connection:
        return renew_run_lease(
            connection,
            organization_id=organization_id,
            run_id=run_id,
            owner=owner,
            ttl_seconds=settings.worker_lease_seconds,
        )
