"""T25: the consumer loop against a real Redis.

Marked `redis` and not `db`: everything here is about how the loop treats a message — when it
acknowledges, what it dead-letters, what it leaves pending for reclaim — so the handler itself is
stubbed. That is the point of the split. `test_stream_worker_db.py` runs the real handlers.

A fake server would be the wrong tool for this file. `XAUTOCLAIM`'s idle accounting, the pending
entries list and `times_delivered` are precisely what is being asserted, and a reimplementation
of them would only prove that the reimplementation agrees with itself.
"""

from __future__ import annotations

import asyncio
import json
from uuid import uuid4

import pytest

from opspilot_agent.streams import consumer as consumer_module
from opspilot_agent.streams.consumer import (
    MAX_ATTEMPTS_ERROR_CODE,
    StreamConsumer,
    queue_health,
)
from opspilot_agent.streams.dispatch import DispatchResult, EventRejected, LeaseUnavailable
from opspilot_agent.streams.envelope import (
    AGENT_RUN_REQUESTED,
    KNOWLEDGE_INGEST_REQUESTED,
    PAYLOAD_FIELD,
    SCHEMA_VERSION,
    EnvelopeVerdict,
)

pytestmark = pytest.mark.redis


async def _publish(client, settings, **overrides) -> tuple[str, dict]:
    """Writes one envelope exactly as T24's Java publisher does: one JSON field named `envelope`."""
    document = {
        "eventId": str(uuid4()),
        "type": KNOWLEDGE_INGEST_REQUESTED,
        "aggregateId": str(uuid4()),
        "organizationId": str(uuid4()),
        "schemaVersion": SCHEMA_VERSION,
        "createdAt": "2026-11-11T12:00:00Z",
    }
    document.update(overrides)
    message_id = await client.xadd(
        settings.redis_stream_name, {PAYLOAD_FIELD: json.dumps(document)}
    )
    return message_id, document


def _stub_dispatch(monkeypatch, behaviour):
    """Replaces the handler with `behaviour(envelope)`, recording every envelope it receives."""
    seen = []

    async def fake_dispatch(envelope, *, settings, owner):
        seen.append(envelope)
        return await behaviour(envelope)

    monkeypatch.setattr(consumer_module, "dispatch", fake_dispatch)
    return seen


async def _ok(envelope) -> DispatchResult:
    return DispatchResult(
        event_id=envelope.event_id, type=envelope.type, executed=True, status="READY"
    )


async def _pending_count(client, settings) -> int:
    summary = await client.xpending(settings.redis_stream_name, settings.redis_consumer_group)
    return int(summary["pending"])


async def _dlq_records(client, settings) -> list[dict]:
    entries = await client.xrange(settings.redis_dlq_stream_name)
    return [fields for _entry_id, fields in entries]


# --- group creation -----------------------------------------------------------------------------


async def test_the_group_is_created_once_and_recreating_it_is_not_an_error(
    redis_client, redis_settings
):
    worker = StreamConsumer(redis_settings, redis=redis_client, owner="worker-1")

    assert await worker.ensure_group() is True
    # BUSYGROUP on every restart after the first. Reported as "did not create", never raised.
    assert await worker.ensure_group() is False


async def test_a_worker_can_start_before_any_event_has_ever_been_published(
    redis_client, redis_settings
):
    # mkstream: the group is created on a stream that does not exist yet, so start order between
    # the API and the worker does not matter.
    worker = StreamConsumer(redis_settings, redis=redis_client, owner="worker-1")
    assert await worker.ensure_group() is True
    assert await redis_client.xlen(redis_settings.redis_stream_name) == 0


async def test_an_event_published_before_the_group_existed_is_still_delivered(
    redis_client, redis_settings, monkeypatch
):
    # The group is created from offset 0, not `$`. Creating it at `$` would silently skip every
    # event already on the stream — the failure this ordering exists to prevent.
    await _publish(redis_client, redis_settings)

    worker = StreamConsumer(redis_settings, redis=redis_client, owner="worker-1")
    seen = _stub_dispatch(monkeypatch, _ok)
    await worker.ensure_group()
    await worker.process_once()

    assert len(seen) == 1


# --- acknowledgement ordering -------------------------------------------------------------------


async def test_a_handled_message_is_acknowledged(redis_client, redis_settings, monkeypatch):
    await _publish(redis_client, redis_settings)
    worker = StreamConsumer(redis_settings, redis=redis_client, owner="worker-1")
    _stub_dispatch(monkeypatch, _ok)

    await worker.ensure_group()
    await worker.process_once()

    assert await _pending_count(redis_client, redis_settings) == 0
    assert worker.metrics.handled == 1


async def test_the_message_is_still_pending_while_the_handler_runs(
    redis_client, redis_settings, monkeypatch
):
    """The ordering rule, observed rather than asserted from the source.

    T25: "Never ACK successful job before durable status/checkpoint persist." If the ACK happened
    first, a crash inside the handler would lose the job; this test fails if that order is ever
    reversed.
    """
    await _publish(redis_client, redis_settings)
    pending_during_handler = []

    async def inspect(envelope):
        pending_during_handler.append(await _pending_count(redis_client, redis_settings))
        return await _ok(envelope)

    worker = StreamConsumer(redis_settings, redis=redis_client, owner="worker-1")
    _stub_dispatch(monkeypatch, inspect)
    await worker.ensure_group()
    await worker.process_once()

    assert pending_during_handler == [1]
    assert await _pending_count(redis_client, redis_settings) == 0


async def test_a_correctly_ignored_replay_is_acknowledged_too(
    redis_client, redis_settings, monkeypatch
):
    # A replay of finished work does nothing — but it must not stay pending for ever, or the
    # group's backlog would grow with messages that will never do anything.
    await _publish(redis_client, redis_settings)

    async def already_done(envelope):
        return DispatchResult(
            event_id=envelope.event_id,
            type=envelope.type,
            executed=False,
            status="COMPLETED",
            detail="RUN_ALREADY_TERMINAL",
        )

    worker = StreamConsumer(redis_settings, redis=redis_client, owner="worker-1")
    _stub_dispatch(monkeypatch, already_done)
    await worker.ensure_group()
    await worker.process_once()

    assert await _pending_count(redis_client, redis_settings) == 0
    assert worker.metrics.skipped == 1
    assert worker.metrics.handled == 0


# --- dead-lettering ------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("overrides", "expected_reason"),
    [
        ({"type": "ticket.invented"}, EnvelopeVerdict.UNKNOWN_TYPE.value),
        ({"schemaVersion": 99}, EnvelopeVerdict.UNKNOWN_SCHEMA_VERSION.value),
    ],
)
async def test_an_unactionable_envelope_is_dead_lettered_and_acknowledged(
    redis_client, redis_settings, monkeypatch, overrides, expected_reason
):
    await _publish(redis_client, redis_settings, **overrides)
    worker = StreamConsumer(redis_settings, redis=redis_client, owner="worker-1")
    seen = _stub_dispatch(monkeypatch, _ok)

    await worker.ensure_group()
    await worker.process_once()

    assert seen == []  # never reached a handler
    records = await _dlq_records(redis_client, redis_settings)
    assert len(records) == 1
    assert records[0]["reason"] == expected_reason
    # Acknowledged, because no amount of retrying makes it routable.
    assert await _pending_count(redis_client, redis_settings) == 0
    assert worker.metrics.dead_lettered == 1


async def test_a_malformed_message_is_dead_lettered_without_an_event_id(
    redis_client, redis_settings, monkeypatch
):
    message_id = await redis_client.xadd(
        redis_settings.redis_stream_name, {PAYLOAD_FIELD: "{not json"}
    )
    worker = StreamConsumer(redis_settings, redis=redis_client, owner="worker-1")
    _stub_dispatch(monkeypatch, _ok)

    await worker.ensure_group()
    await worker.process_once()

    records = await _dlq_records(redis_client, redis_settings)
    assert len(records) == 1
    assert records[0]["reason"] == EnvelopeVerdict.MALFORMED.value
    # Nothing could be parsed, so the only handle an operator gets is the stream id — which is
    # recorded for exactly that reason.
    assert records[0]["originalMessageId"] == message_id
    assert "eventId" not in records[0]


async def test_a_rejected_event_is_dead_lettered_with_its_code(
    redis_client, redis_settings, monkeypatch
):
    await _publish(redis_client, redis_settings)

    async def rejected(envelope):
        raise EventRejected("DOCUMENT_NOT_FOUND")

    worker = StreamConsumer(redis_settings, redis=redis_client, owner="worker-1")
    _stub_dispatch(monkeypatch, rejected)
    await worker.ensure_group()
    await worker.process_once()

    records = await _dlq_records(redis_client, redis_settings)
    assert records[0]["reason"] == "DOCUMENT_NOT_FOUND"
    assert await _pending_count(redis_client, redis_settings) == 0


async def test_the_dead_letter_record_carries_identifiers_and_codes_only(
    redis_client, redis_settings, monkeypatch
):
    """docs/SECURITY_AND_TESTING.md: a dead-letter reason must not leak data.

    The handler here raises with a message full of exactly the kind of text that must never reach
    an operator-visible stream. The record must contain the *class* of failure, not the words.
    """
    secret = "customer bank details 4111111111111111"
    _, document = await _publish(redis_client, redis_settings, type=AGENT_RUN_REQUESTED)

    async def explode(envelope):
        raise RuntimeError(secret)

    worker = StreamConsumer(redis_settings, redis=redis_client, owner="worker-1")
    _stub_dispatch(monkeypatch, explode)
    await worker.ensure_group()

    # Run it past the attempt cap so it is finally dead-lettered.
    for _ in range(redis_settings.worker_max_attempts + 1):
        await worker.process_once()

    records = await _dlq_records(redis_client, redis_settings)
    assert len(records) == 1
    record = records[0]
    assert record["reason"] == MAX_ATTEMPTS_ERROR_CODE
    assert record["eventId"] == document["eventId"]
    assert record["type"] == AGENT_RUN_REQUESTED
    assert record["worker"] == "worker-1"
    assert int(record["deliveryCount"]) > redis_settings.worker_max_attempts
    # The whole point:
    assert secret not in json.dumps(record)
    assert "RuntimeError" not in json.dumps(record)


# --- retries, backoff and the attempt cap -------------------------------------------------------


async def test_a_failed_handler_leaves_the_message_pending_to_be_retried(
    redis_client, redis_settings, monkeypatch
):
    await _publish(redis_client, redis_settings)
    attempts = []

    async def fail(envelope):
        attempts.append(1)
        raise RuntimeError("transient")

    worker = StreamConsumer(redis_settings, redis=redis_client, owner="worker-1")
    _stub_dispatch(monkeypatch, fail)
    await worker.ensure_group()

    await worker.process_once()
    assert len(attempts) == 1
    # Unacknowledged: the pending entry *is* the retry record. Nothing else is written.
    assert await _pending_count(redis_client, redis_settings) == 1
    assert worker.metrics.retried == 1
    assert await _dlq_records(redis_client, redis_settings) == []

    # The next pass reclaims and retries it.
    await worker.process_once()
    assert len(attempts) == 2
    assert worker.metrics.reclaimed == 1


async def test_delivery_count_comes_from_redis_and_grows_with_each_attempt(
    redis_client, redis_settings, monkeypatch
):
    await _publish(redis_client, redis_settings)
    counts = []

    async def record_attempt(envelope):
        counts.append(await worker._delivery_count(message_ids[-1]))
        raise RuntimeError("transient")

    worker = StreamConsumer(redis_settings, redis=redis_client, owner="worker-1")
    _stub_dispatch(monkeypatch, record_attempt)
    await worker.ensure_group()

    message_ids = [
        entry_id for entry_id, _ in await redis_client.xrange(redis_settings.redis_stream_name)
    ]
    await worker.process_once()
    await worker.process_once()

    # No counter of our own: Redis increments times_delivered on every claim.
    assert counts == [1, 2]


async def test_the_attempt_cap_dead_letters_and_stops_retrying(
    redis_client, redis_settings, monkeypatch
):
    await _publish(redis_client, redis_settings)
    attempts = []

    async def fail(envelope):
        attempts.append(1)
        raise RuntimeError("still broken")

    worker = StreamConsumer(redis_settings, redis=redis_client, owner="worker-1")
    _stub_dispatch(monkeypatch, fail)
    await worker.ensure_group()

    for _ in range(redis_settings.worker_max_attempts + 3):
        await worker.process_once()

    # Tried exactly max_attempts times, then given up on — no endless tight retry loop.
    assert len(attempts) == redis_settings.worker_max_attempts
    assert len(await _dlq_records(redis_client, redis_settings)) == 1
    assert await _pending_count(redis_client, redis_settings) == 0


def test_backoff_is_exponential_and_bounded(redis_settings):
    worker = StreamConsumer(
        redis_settings.model_copy(
            update={"worker_backoff_base_seconds": 2.0, "worker_backoff_max_seconds": 10.0}
        ),
        redis=object(),
        owner="worker-1",
    )

    assert worker._backoff_seconds(1) == 2.0
    assert worker._backoff_seconds(2) == 4.0
    assert worker._backoff_seconds(3) == 8.0
    # Capped, so a long outage is not an exponentially growing wait.
    assert worker._backoff_seconds(4) == 10.0
    assert worker._backoff_seconds(40) == 10.0


# --- reclaim and worker death -------------------------------------------------------------------


async def test_a_dead_workers_message_is_reclaimed_by_another(
    redis_client, redis_settings, monkeypatch
):
    """Worker death mid-run: the entry stays pending for the dead consumer and is claimed.

    The first worker reads the message and then "dies" — simulated by never acknowledging and
    never running again, which is indistinguishable from a kill -9 as far as Redis is concerned.
    """
    await _publish(redis_client, redis_settings)

    dead = StreamConsumer(redis_settings, redis=redis_client, owner="worker-dead")
    await dead.ensure_group()
    read = await dead._read_new()
    assert len(read) == 1
    assert await _pending_count(redis_client, redis_settings) == 1

    survivor = StreamConsumer(redis_settings, redis=redis_client, owner="worker-survivor")
    seen = _stub_dispatch(monkeypatch, _ok)
    await survivor.process_once()

    assert len(seen) == 1
    assert survivor.metrics.reclaimed == 1
    assert survivor.metrics.handled == 1
    assert await _pending_count(redis_client, redis_settings) == 0


async def test_a_reclaimed_entry_whose_message_was_trimmed_away_is_skipped(
    redis_client, redis_settings, monkeypatch
):
    # XAUTOCLAIM reports entries whose underlying message no longer exists; there is nothing to
    # process and nothing to dead-letter, and treating them as malformed would be a lie.
    message_id, _ = await _publish(redis_client, redis_settings)

    dead = StreamConsumer(redis_settings, redis=redis_client, owner="worker-dead")
    await dead.ensure_group()
    await dead._read_new()
    await redis_client.xdel(redis_settings.redis_stream_name, message_id)

    survivor = StreamConsumer(redis_settings, redis=redis_client, owner="worker-survivor")
    seen = _stub_dispatch(monkeypatch, _ok)
    await survivor.process_once()

    assert seen == []
    assert await _dlq_records(redis_client, redis_settings) == []


async def test_a_lease_held_elsewhere_leaves_the_message_pending_and_is_not_a_failure(
    redis_client, redis_settings, monkeypatch
):
    await _publish(redis_client, redis_settings)

    async def leased_elsewhere(envelope):
        raise LeaseUnavailable("another worker has it")

    worker = StreamConsumer(redis_settings, redis=redis_client, owner="worker-1")
    _stub_dispatch(monkeypatch, leased_elsewhere)
    await worker.ensure_group()
    await worker.process_once()

    # Not acknowledged — if the holder dies, this entry must still be reclaimable. Not
    # dead-lettered and not counted as a retry either: nothing failed.
    assert await _pending_count(redis_client, redis_settings) == 1
    assert await _dlq_records(redis_client, redis_settings) == []
    assert worker.metrics.lease_conflicts == 1
    assert worker.metrics.retried == 0


async def test_two_workers_sharing_a_group_each_handle_part_of_the_backlog(
    redis_client, redis_settings, monkeypatch
):
    for _ in range(6):
        await _publish(redis_client, redis_settings)

    first = StreamConsumer(redis_settings, redis=redis_client, owner="worker-1")
    second = StreamConsumer(redis_settings, redis=redis_client, owner="worker-2")
    await first.ensure_group()

    seen = _stub_dispatch(monkeypatch, _ok)
    # Batch size is 10, so the first read would take everything; limit it so both get work.
    first.settings = redis_settings.model_copy(update={"redis_batch_size": 3})
    second.settings = first.settings
    await first.process_once()
    await second.process_once()

    assert first.metrics.handled == 3
    assert second.metrics.handled == 3
    assert len({envelope.event_id for envelope in seen}) == 6
    assert await _pending_count(redis_client, redis_settings) == 0


# --- shutdown and outage ------------------------------------------------------------------------


async def test_run_forever_returns_when_signalled(redis_client, redis_settings, monkeypatch):
    _stub_dispatch(monkeypatch, _ok)
    worker = StreamConsumer(redis_settings, redis=redis_client, owner="worker-1")
    stop = asyncio.Event()

    async def signal_soon():
        await asyncio.sleep(0.1)
        stop.set()

    # Returns rather than being cancelled: a shutdown must not interrupt a message mid-flight.
    await asyncio.wait_for(asyncio.gather(worker.run_forever(stop), signal_soon()), timeout=10)
    assert stop.is_set()


async def test_the_loop_survives_redis_being_unreachable(redis_settings, monkeypatch):
    # Port 1 is never a Redis. The loop must log and back off, not die — and the backlog is safe
    # regardless, because the publisher's outbox is the durable record (ADR-048).
    from opspilot_agent.streams.client import build_redis

    unreachable = redis_settings.model_copy(update={"redis_port": 1})
    client = build_redis(unreachable)
    worker = StreamConsumer(unreachable, redis=client, owner="worker-1")
    stop = asyncio.Event()

    async def signal_soon():
        await asyncio.sleep(0.2)
        stop.set()

    try:
        await asyncio.wait_for(asyncio.gather(worker.run_forever(stop), signal_soon()), timeout=15)
    finally:
        await client.aclose()


async def test_the_group_is_recreated_if_it_disappears(redis_client, redis_settings, monkeypatch):
    """A Redis restart without persistence takes the group with it.

    XREADGROUP then fails with NOGROUP, which no amount of retrying clears on its own — the loop
    has to notice and recreate the group. Without that branch a worker would spin on the same
    error until someone restarted it.
    """
    worker = StreamConsumer(redis_settings, redis=redis_client, owner="worker-1")
    await worker.ensure_group()
    await redis_client.xgroup_destroy(
        redis_settings.redis_stream_name, redis_settings.redis_consumer_group
    )

    _stub_dispatch(monkeypatch, _ok)
    stop = asyncio.Event()

    async def signal_soon():
        await asyncio.sleep(0.4)
        stop.set()

    await asyncio.wait_for(asyncio.gather(worker.run_forever(stop), signal_soon()), timeout=15)

    groups = await redis_client.xinfo_groups(redis_settings.redis_stream_name)
    assert [group["name"] for group in groups] == [redis_settings.redis_consumer_group]


# --- queue health -------------------------------------------------------------------------------


async def test_queue_health_reports_depth_pending_and_dead_letters(
    redis_client, redis_settings, monkeypatch
):
    worker = StreamConsumer(redis_settings, redis=redis_client, owner="worker-1")
    await worker.ensure_group()
    for _ in range(3):
        await _publish(redis_client, redis_settings)

    before = await queue_health(redis_client, redis_settings)
    assert before["streamLength"] == 3
    assert before["pending"] == 0
    assert before["deadLetterLength"] == 0

    # Read without acknowledging, so there is a backlog to see.
    await worker._read_new()
    after = await queue_health(redis_client, redis_settings)

    assert after["pending"] == 3
    assert after["consumers"] == 1
    assert after["group"] == redis_settings.redis_consumer_group


async def test_queue_health_on_a_stream_that_does_not_exist_yet(redis_client, redis_settings):
    """A fresh deployment, before the first event has ever been published.

    Found by running `python -m opspilot_agent.streams --health` against an empty Redis: XLEN
    answers 0 for a missing key, but XINFO GROUPS raises "no such key", so this crashed where the
    honest answer is that there is nothing to report yet.
    """
    health = await queue_health(redis_client, redis_settings)

    assert health["streamLength"] == 0
    assert health["deadLetterLength"] == 0
    assert health["pending"] is None
    assert health["consumers"] == 0


async def test_queue_health_on_a_stream_with_no_group_reports_unknown_not_zero(
    redis_client, redis_settings
):
    # An absent group means the backlog is unknown. Reporting 0 would read as "nothing waiting",
    # which is the opposite of the truth when a worker has never started.
    await _publish(redis_client, redis_settings)

    health = await queue_health(redis_client, redis_settings)

    assert health["streamLength"] == 1
    assert health["pending"] is None
    assert health["consumers"] == 0
