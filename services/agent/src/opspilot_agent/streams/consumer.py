"""The consumer loop: read, reclaim, run, acknowledge.

The ordering rule the whole thing is built around, from docs/TASKS.md T25: **never ACK a
successful job before durable status/checkpoint persist.** So `XACK` is the last thing that
happens, after the handler's own transaction has committed. A crash in the window between those
two is a redelivery — which every handler is built to absorb — whereas acknowledging first would
turn the same crash into silently lost work.

Retries are the pending-entries list, not a sleep loop. A handler that fails leaves its message
unacknowledged; the message then sits in this consumer's PEL and is picked up again by a later
reclaim pass, by this worker or by another one. That is what makes "worker death mid-run"
recoverable: a process that dies holding messages leaves them pending, and `XAUTOCLAIM` after
`worker_reclaim_min_idle_ms` hands them to whoever is still alive.

Attempt counting comes from Redis's own `times_delivered` rather than a table of our own. It is
already durable, already per-message, and already incremented by the claim — inventing a second
counter would mean two sources of truth that could disagree after a crash.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field

import psycopg
from redis.asyncio import Redis
from redis.exceptions import RedisError, ResponseError

from opspilot_agent.config import Settings, get_settings
from opspilot_agent.db import connect
from opspilot_agent.streams import dlq
from opspilot_agent.streams.client import build_redis
from opspilot_agent.streams.dispatch import (
    EventRejected,
    LeaseUnavailable,
    dispatch,
)
from opspilot_agent.streams.envelope import (
    AGENT_RUN_REQUESTED,
    AGENT_RUN_RESUME,
    Envelope,
    parse_envelope,
)
from opspilot_agent.streams.leases import default_owner_name

log = logging.getLogger(__name__)

RUN_EVENT_TYPES = frozenset({AGENT_RUN_REQUESTED, AGENT_RUN_RESUME})

MAX_ATTEMPTS_ERROR_CODE = "WORKER_MAX_ATTEMPTS_EXCEEDED"
"""Written to `agent_runs.last_error_code` when a run's event is finally given up on.

A code, not a message: whatever the handler actually raised may quote ticket text or a provider
response, and docs/SECURITY_AND_TESTING.md forbids putting that in a log, let alone a column.
"""


@dataclass
class ConsumerMetrics:
    """What this process has done, for the queue-health endpoint and for tests.

    Counters only — no timings. Latency numbers would need a measurement story this ticket has no
    way to validate, and AGENTS.md rule 6 forbids inventing metrics.
    """

    handled: int = 0
    skipped: int = 0
    reclaimed: int = 0
    dead_lettered: int = 0
    retried: int = 0
    lease_conflicts: int = 0
    by_type: dict[str, int] = field(default_factory=dict)

    def record(self, event_type: str) -> None:
        self.by_type[event_type] = self.by_type.get(event_type, 0) + 1


class StreamConsumer:
    """One worker's view of `opspilot:events`."""

    def __init__(
        self,
        settings: Settings | None = None,
        *,
        redis: Redis | None = None,
        owner: str | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self.redis = redis if redis is not None else build_redis(self.settings)
        # One name for both roles. See `default_owner_name` for why they are not separate.
        self.owner = owner or default_owner_name(self.settings)
        self.metrics = ConsumerMetrics()

    @property
    def stream(self) -> str:
        return self.settings.redis_stream_name

    @property
    def group(self) -> str:
        return self.settings.redis_consumer_group

    async def ensure_group(self) -> bool:
        """Creates the consumer group if it is missing. True if this call created it.

        T24's publisher creates the same group from offset 0, and so does this: whichever side
        starts first, the group exists before anything is read, and a group created at `$` would
        have silently skipped everything already published. `mkstream` lets a worker start before
        a single event has ever been produced.
        """
        try:
            await self.redis.xgroup_create(self.stream, self.group, id="0", mkstream=True)
        except ResponseError as error:
            if "BUSYGROUP" in str(error):
                # The normal case on every start after the first.
                return False
            raise
        log.info("Created consumer group %s on %s.", self.group, self.stream)
        return True

    async def run_forever(self, stop: asyncio.Event | None = None) -> None:
        """Reads and processes until `stop` is set.

        The signal is checked between batches rather than mid-message: a message being handled is
        allowed to finish so that its durable write and its ACK stay in the right order. Worst
        case on an abrupt kill, the message stays pending and is reclaimed — which is exactly the
        path this design already relies on.
        """
        stop = stop or asyncio.Event()
        # Group creation is inside the loop, not before it. A worker started while Redis is down
        # must back off and recover rather than exit — found by testing it: with the call outside,
        # an unreachable Redis at startup took the process down before the loop ever ran.
        group_ready = False

        while not stop.is_set():
            try:
                if not group_ready:
                    await self.ensure_group()
                    group_ready = True
                await self.process_once()
            except RedisError as error:
                # Redis went away mid-loop. Nothing is lost: unacknowledged messages stay pending,
                # and the publisher's outbox keeps the backlog durable regardless (ADR-048).
                if "NOGROUP" in str(error):
                    # The stream or the group vanished — a Redis restart without persistence, for
                    # instance. Recreate it on the next pass instead of looping on an error that
                    # retrying alone can never clear.
                    group_ready = False
                log.warning(
                    "Redis error in the consumer loop (%s); backing off before retrying.",
                    type(error).__name__,
                )
                await self._sleep_or_stop(
                    stop, max(self.settings.worker_backoff_base_seconds, 0.05)
                )

        log.info("Consumer %s stopping; %s", self.owner, self.metrics)

    async def process_once(self) -> int:
        """One reclaim pass plus one blocking read. Returns how many messages were processed."""
        processed = 0

        for message_id, fields, delivery_count in await self._reclaim_idle():
            self.metrics.reclaimed += 1
            await self._handle(message_id, fields, delivery_count)
            processed += 1

        for message_id, fields in await self._read_new():
            # `>` only ever yields messages never delivered to anyone, so this is attempt 1.
            await self._handle(message_id, fields, delivery_count=1)
            processed += 1

        return processed

    async def _read_new(self) -> list[tuple[str, dict[str, str]]]:
        response = await self.redis.xreadgroup(
            groupname=self.group,
            consumername=self.owner,
            streams={self.stream: ">"},
            count=self.settings.redis_batch_size,
            block=self.settings.redis_block_ms,
        )
        if not response:
            return []
        # [(stream, [(id, {field: value}), ...])] — one stream, so one element.
        return [
            (message_id, fields) for _stream, entries in response for message_id, fields in entries
        ]

    async def _reclaim_idle(self) -> list[tuple[str, dict[str, str], int]]:
        """Claims entries another worker (or an earlier incarnation of this one) left pending.

        `min_idle_time` is kept at or above the lease TTL on purpose: claiming the message sooner
        than the run's lease expires would hand the work to a worker that then cannot take the
        lease and would have to give it straight back.
        """
        _next_cursor, entries, *_ = await self.redis.xautoclaim(
            name=self.stream,
            groupname=self.group,
            consumername=self.owner,
            min_idle_time=self.settings.worker_reclaim_min_idle_ms,
            start_id="0-0",
            count=self.settings.redis_batch_size,
        )

        claimed: list[tuple[str, dict[str, str], int]] = []
        for message_id, fields in entries:
            if fields is None:
                # XAUTOCLAIM reports — and clears — entries whose message no longer exists in the
                # stream (trimmed away). There is nothing to process and nothing to dead-letter.
                continue
            claimed.append((message_id, fields, await self._delivery_count(message_id)))
        return claimed

    async def _delivery_count(self, message_id: str) -> int:
        """How many times Redis has handed this entry out, including the claim just made."""
        pending = await self.redis.xpending_range(
            name=self.stream, groupname=self.group, min=message_id, max=message_id, count=1
        )
        if not pending:
            return 1
        return int(pending[0]["times_delivered"])

    async def _handle(self, message_id: str, fields: dict[str, str], delivery_count: int) -> None:
        envelope, verdict = parse_envelope(fields)

        if not verdict.accepted:
            # Broken, foreign-versioned or unroutable. Retrying cannot change any of those, so it
            # goes straight to the dead-letter stream and is acknowledged — leaving it pending
            # would grow the PEL forever with messages nothing will ever process.
            await self._dead_letter(message_id, envelope, verdict.value, delivery_count)
            return

        assert envelope is not None  # ACCEPTED implies a parsed envelope.

        if delivery_count > self.settings.worker_max_attempts:
            await self._give_up(message_id, envelope, delivery_count)
            return

        try:
            result = await dispatch(envelope, settings=self.settings, owner=self.owner)
        except EventRejected as rejected:
            await self._dead_letter(message_id, envelope, rejected.code, delivery_count)
            return
        except LeaseUnavailable:
            # Someone else is running it. Deliberately not acknowledged and deliberately not a
            # failure: if that worker dies, this entry must still be reclaimable.
            self.metrics.lease_conflicts += 1
            log.info("Run for event %s is leased elsewhere; leaving it pending.", envelope.event_id)
            return
        except Exception as error:  # noqa: BLE001 - classified, recorded, and re-delivered
            # Unacknowledged on purpose: the retry *is* the pending entry. Backoff is bounded and
            # grows with the delivery count, so a persistently broken handler slows down instead
            # of spinning.
            self.metrics.retried += 1
            log.warning(
                "Handler for event %s failed (attempt %d/%d, %s); leaving it pending to retry.",
                envelope.event_id,
                delivery_count,
                self.settings.worker_max_attempts,
                type(error).__name__,
            )
            await asyncio.sleep(self._backoff_seconds(delivery_count))
            return

        # Durable first, acknowledged second. The handler has committed its status (and, for a
        # run, its checkpoint) before this line is reached.
        await self.redis.xack(self.stream, self.group, message_id)

        if result.executed:
            self.metrics.handled += 1
        else:
            self.metrics.skipped += 1
        self.metrics.record(result.type)

    async def _give_up(self, message_id: str, envelope: Envelope, delivery_count: int) -> None:
        """Past the attempt cap: dead-letter it, and leave the run terminally FAILED."""
        if envelope.type in RUN_EVENT_TYPES:
            self._mark_run_failed(envelope)
        await self._dead_letter(message_id, envelope, MAX_ATTEMPTS_ERROR_CODE, delivery_count)

    def _mark_run_failed(self, envelope: Envelope) -> None:
        """Records the terminal failure on the run, with a code rather than a reason string.

        Only from a non-terminal status: a run that reached COMPLETED on an earlier delivery must
        not be rewritten to FAILED because a later duplicate ran out of attempts.
        """
        try:
            with connect(self.settings) as connection:
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        UPDATE agent_runs
                           SET status = 'FAILED',
                               last_error_code = %s,
                               lease_owner = NULL,
                               lease_expires_at = NULL,
                               updated_at = now()
                         WHERE id = %s
                           AND organization_id = %s
                           AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                        """,
                        (MAX_ATTEMPTS_ERROR_CODE, envelope.aggregate_id, envelope.organization_id),
                    )
                connection.commit()
        except psycopg.Error:
            # The dead-letter record still goes out, which is the operator-visible part. Losing
            # the row update must not also lose the dead-letter.
            log.exception(
                "Could not mark run %s failed after exhausting attempts.", envelope.aggregate_id
            )

    async def _dead_letter(
        self,
        message_id: str,
        envelope: Envelope | None,
        reason: str,
        delivery_count: int,
    ) -> None:
        await dlq.dead_letter(
            self.redis,
            stream=self.settings.redis_dlq_stream_name,
            message_id=message_id,
            envelope=envelope,
            reason=reason,
            delivery_count=delivery_count,
            worker=self.owner,
        )
        # Acknowledged *after* the dead-letter record is durable, for the same reason a success is
        # acknowledged after its write: the order is what makes a crash a duplicate rather than a
        # disappearance.
        await self.redis.xack(self.stream, self.group, message_id)
        self.metrics.dead_lettered += 1

    def _backoff_seconds(self, delivery_count: int) -> float:
        """Bounded exponential backoff: base × 2^(attempt-1), capped."""
        exponent = max(delivery_count - 1, 0)
        return min(
            self.settings.worker_backoff_base_seconds * (2**exponent),
            self.settings.worker_backoff_max_seconds,
        )

    @staticmethod
    async def _sleep_or_stop(stop: asyncio.Event, seconds: float) -> None:
        """Waits, but wakes immediately on shutdown rather than sitting out the delay."""
        try:
            await asyncio.wait_for(stop.wait(), timeout=seconds)
        except TimeoutError:
            return


async def queue_health(redis: Redis, settings: Settings) -> dict[str, object]:
    """Queue depth and backlog, for the worker's health endpoint.

    Read straight from Redis rather than accumulated in this process: a single worker's counters
    say nothing about the backlog, and docs/API_CONTRACT.md asks to "alert on backlog".
    """
    stream = settings.redis_stream_name
    health: dict[str, object] = {
        "stream": stream,
        "group": settings.redis_consumer_group,
    }

    health["streamLength"] = await redis.xlen(stream)
    health["deadLetterLength"] = await redis.xlen(settings.redis_dlq_stream_name)

    try:
        groups = await redis.xinfo_groups(stream)
    except ResponseError as error:
        if "no such key" not in str(error).lower():
            raise
        # The stream does not exist yet, which is the normal state of a fresh deployment before
        # the first event is published. Found by running `--health` on an empty Redis: XLEN
        # answers 0 for a missing key but XINFO GROUPS raises, so this reported an error where
        # the honest answer is "nothing here yet".
        groups = []

    for group in groups:
        if group.get("name") == settings.redis_consumer_group:
            health["pending"] = group.get("pending")
            # `lag` is how many entries the group has never seen. None on older servers, and
            # reported as such rather than as zero — an unknown backlog is not an empty one.
            health["lag"] = group.get("lag")
            health["consumers"] = group.get("consumers")
            break
    else:
        health["pending"] = None
        health["lag"] = None
        health["consumers"] = 0

    health["oldestPendingIdleMs"] = await _oldest_pending_idle_ms(redis, settings)
    return health


async def _oldest_pending_idle_ms(redis: Redis, settings: Settings) -> int | None:
    """How long the longest-waiting unacknowledged entry has gone without progress.

    docs/SECURITY_AND_TESTING.md §7 alerts on "queue backlog age", and depth alone does not answer
    it: a queue of two that has not moved in an hour is a worse state than a queue of two hundred
    being worked through. This is the number that distinguishes them.

    The oldest entry, not an average — the same reasoning as the API's oldest-pending-approval
    gauge. A mean hides the one entry that is stuck behind the many that are fine.

    None, never zero, when there is nothing pending or the stream does not exist yet: zero would
    read as "nothing is waiting and we measured that", and an alert threshold sits just above zero.
    """
    try:
        entries = await redis.xpending_range(
            settings.redis_stream_name,
            settings.redis_consumer_group,
            min="-",
            max="+",
            count=1,
        )
    except ResponseError as error:
        if "no such key" in str(error).lower() or "nogroup" in str(error).lower():
            return None
        raise

    if not entries:
        return None
    idle = entries[0].get("time_since_delivered")
    return int(idle) if idle is not None else None
