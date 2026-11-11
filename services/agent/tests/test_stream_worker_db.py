"""T25 end to end: a message on a real stream, a real handler, a real database.

Everything between `XADD` and the durable effect, with nothing stubbed. The consumer loop is
covered on its own in `test_stream_consumer_redis.py` and dispatch on its own in
`test_stream_dispatch_db.py`; what only this file can show is that the two halves agree — that the
envelope T24's publisher writes is the envelope this worker reads, and that the effect lands.
"""

from __future__ import annotations

import json
from uuid import UUID, uuid4

import httpx
import pytest
from pydantic import SecretStr
from redis.exceptions import RedisError

from opspilot_agent.clients.internal_actions import InternalActionClient
from opspilot_agent.knowledge.storage import LocalObjectStore
from opspilot_agent.streams import consumer as consumer_module
from opspilot_agent.streams.consumer import MAX_ATTEMPTS_ERROR_CODE, StreamConsumer
from opspilot_agent.streams.envelope import (
    AGENT_RUN_REQUESTED,
    AGENT_RUN_RESUME,
    KNOWLEDGE_INGEST_REQUESTED,
    PAYLOAD_FIELD,
    SCHEMA_VERSION,
)
from tests.conftest import insert_document, insert_run, insert_ticket, insert_user

pytestmark = [pytest.mark.redis, pytest.mark.db]

FAQ_TEXT = b"""# Shipping FAQ

Standard delivery takes three to five working days. A parcel counts as lost after ten working
days without a scan.
"""


SECRET = "a-local-test-secret-long-enough-for-hs256"


@pytest.fixture
def settings(worker_settings, tmp_path):
    # The worker reads documents from the same local root the Java adapter writes to (ADR-040).
    return worker_settings.model_copy(
        update={
            "storage_local_dir": str(tmp_path),
            "internal_service_signing_secret": SecretStr(SECRET),
        }
    )


class RecordingApi:
    """Stands in for services/api's internal action endpoint, recording what it receives."""

    def __init__(self) -> None:
        self.calls: list[dict] = []

    def client(self, settings) -> InternalActionClient:
        def handler(request: httpx.Request) -> httpx.Response:
            body = json.loads(request.content)
            self.calls.append(body)
            return httpx.Response(
                200,
                json={
                    "toolCallId": body["toolCallId"],
                    "status": "SUCCEEDED",
                    "resultReference": f"ticket_message:{uuid4()}",
                    "alreadyApplied": False,
                },
            )

        return InternalActionClient(
            settings,
            client=httpx.AsyncClient(
                transport=httpx.MockTransport(handler), base_url="https://api.internal"
            ),
        )


def _dispatch_with(api: RecordingApi):
    """The real dispatch, with only the HTTP collaborator replaced.

    Not a stub: the graph, the lease and every status check run for real. Replacing the client is
    what makes "the action was performed" observable without a live Java API, the same way
    test_stream_dispatch_db.py does it.
    """
    from opspilot_agent.streams.dispatch import dispatch as real_dispatch

    async def dispatch_with_api(envelope, *, settings, owner):
        return await real_dispatch(
            envelope, settings=settings, owner=owner, internal_actions=api.client(settings)
        )

    return dispatch_with_api


async def _publish(client, settings, *, event_type: str, aggregate_id: UUID, organization_id: UUID):
    """Writes the envelope byte-for-byte the way `OutboxPublisher` does."""
    return await client.xadd(
        settings.redis_stream_name,
        {
            PAYLOAD_FIELD: json.dumps(
                {
                    "eventId": str(uuid4()),
                    "type": event_type,
                    "aggregateId": str(aggregate_id),
                    "organizationId": str(organization_id),
                    "schemaVersion": SCHEMA_VERSION,
                    "createdAt": "2026-11-11T12:00:00Z",
                }
            )
        },
    )


def _stored_document(db_connection, organization, tmp_path, user_id) -> UUID:
    store = LocalObjectStore(tmp_path)
    key = f"orgs/{organization}/knowledge/{uuid4()}"
    path = store.base_dir / key
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(FAQ_TEXT)
    return insert_document(
        db_connection,
        organization_id=organization,
        uploaded_by=user_id,
        filename="shipping.md",
        object_key=key,
    )


def _document_status(connection, document_id) -> str:
    with connection.cursor() as cursor:
        cursor.execute("SELECT status FROM knowledge_documents WHERE id = %s", (document_id,))
        return cursor.fetchone()[0]


def _chunk_count(connection, document_id) -> int:
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT count(*) FROM knowledge_chunks WHERE document_id = %s", (document_id,)
        )
        return cursor.fetchone()[0]


async def _pending(client, settings) -> int:
    summary = await client.xpending(settings.redis_stream_name, settings.redis_consumer_group)
    return int(summary["pending"])


@pytest.fixture
def seeded_run(db_connection, organization):
    user_id = insert_user(db_connection, f"{uuid4()}@example.test")
    ticket_id = insert_ticket(
        db_connection,
        organization_id=organization,
        created_by=user_id,
        subject="Where is my delivery?",
        description="My parcel has not arrived and tracking has not updated in two weeks.",
    )
    run_id = insert_run(
        db_connection,
        organization_id=organization,
        ticket_id=ticket_id,
        initiated_by=user_id,
    )
    return {"user_id": user_id, "ticket_id": ticket_id, "run_id": run_id}


async def test_a_published_ingest_event_is_consumed_indexed_and_acknowledged(
    db_connection, organization, worker_client, settings, seeded_run, tmp_path
):
    document_id = _stored_document(db_connection, organization, tmp_path, seeded_run["user_id"])
    await _publish(
        worker_client,
        settings,
        event_type=KNOWLEDGE_INGEST_REQUESTED,
        aggregate_id=document_id,
        organization_id=organization,
    )

    worker = StreamConsumer(settings, redis=worker_client, owner="worker-1")
    await worker.ensure_group()
    assert await worker.process_once() == 1

    assert _document_status(db_connection, document_id) == "READY"
    assert _chunk_count(db_connection, document_id) > 0
    # Acknowledged only after the index was durable.
    assert await _pending(worker_client, settings) == 0
    assert worker.metrics.by_type == {KNOWLEDGE_INGEST_REQUESTED: 1}


async def test_the_same_event_delivered_twice_has_one_effect(
    db_connection, organization, worker_client, settings, seeded_run, tmp_path
):
    """Two stream entries carrying one eventId — exactly what T24's crash window produces."""
    document_id = _stored_document(db_connection, organization, tmp_path, seeded_run["user_id"])
    envelope_fields = {
        PAYLOAD_FIELD: json.dumps(
            {
                "eventId": str(uuid4()),
                "type": KNOWLEDGE_INGEST_REQUESTED,
                "aggregateId": str(document_id),
                "organizationId": str(organization),
                "schemaVersion": SCHEMA_VERSION,
                "createdAt": "2026-11-11T12:00:00Z",
            }
        )
    }
    await worker_client.xadd(settings.redis_stream_name, envelope_fields)
    await worker_client.xadd(settings.redis_stream_name, envelope_fields)

    worker = StreamConsumer(settings, redis=worker_client, owner="worker-1")
    await worker.ensure_group()
    await worker.process_once()

    assert worker.metrics.handled == 2
    # Two deliveries, one set of chunks: idempotency lives in the unique index, not in a
    # de-duplication table the worker would have to keep in step. Asserted as "every chunk_index
    # appears once", which is the property that would break if the second delivery had appended.
    assert _chunk_count(db_connection, document_id) > 0
    with db_connection.cursor() as cursor:
        cursor.execute(
            "SELECT count(DISTINCT chunk_index), count(*) FROM knowledge_chunks "
            "WHERE document_id = %s",
            (document_id,),
        )
        distinct, total = cursor.fetchone()
    assert distinct == total


async def test_a_crash_before_the_ack_replays_without_a_second_effect(
    db_connection, organization, worker_client, settings, seeded_run, tmp_path, monkeypatch
):
    """The window T24 documented, from the consumer's side.

    The handler commits, then the process dies before `XACK`. The entry is still pending, so it is
    reclaimed and run again — and the second run must not produce a second effect.
    """
    document_id = _stored_document(db_connection, organization, tmp_path, seeded_run["user_id"])
    await _publish(
        worker_client,
        settings,
        event_type=KNOWLEDGE_INGEST_REQUESTED,
        aggregate_id=document_id,
        organization_id=organization,
    )

    worker = StreamConsumer(settings, redis=worker_client, owner="worker-1")
    await worker.ensure_group()

    real_xack = worker_client.xack
    crashed = []

    async def die_before_acking(*args, **kwargs):
        crashed.append(args)
        raise RedisError("process died before acknowledging")

    monkeypatch.setattr(worker_client, "xack", die_before_acking)
    with pytest.raises(RedisError):
        await worker.process_once()

    assert crashed  # the effect happened; the acknowledgement did not
    chunks_after_crash = _chunk_count(db_connection, document_id)
    assert chunks_after_crash > 0
    assert await _pending(worker_client, settings) == 1

    # Recovery: the entry is reclaimed and processed again.
    monkeypatch.setattr(worker_client, "xack", real_xack)
    await worker.process_once()

    assert await _pending(worker_client, settings) == 0
    assert _chunk_count(db_connection, document_id) == chunks_after_crash


async def test_a_published_run_event_drives_the_real_graph(
    db_connection, organization, worker_client, settings, seeded_run
):
    await _publish(
        worker_client,
        settings,
        event_type=AGENT_RUN_REQUESTED,
        aggregate_id=seeded_run["run_id"],
        organization_id=organization,
    )

    worker = StreamConsumer(settings, redis=worker_client, owner="worker-1")
    await worker.ensure_group()
    await worker.process_once()

    with db_connection.cursor() as cursor:
        cursor.execute(
            "SELECT status, lease_owner FROM agent_runs WHERE id = %s", (seeded_run["run_id"],)
        )
        status, lease_owner = cursor.fetchone()

    # The graph proposes a reply, so the run parks on a human rather than completing — and the
    # message is still acknowledged, because the pause *is* a durable state (T23).
    assert status == "AWAITING_APPROVAL"
    assert lease_owner is None
    assert await _pending(worker_client, settings) == 0


async def test_exhausting_attempts_leaves_the_run_terminally_failed_with_a_code(
    db_connection, organization, worker_client, settings, seeded_run, monkeypatch
):
    await _publish(
        worker_client,
        settings,
        event_type=AGENT_RUN_REQUESTED,
        aggregate_id=seeded_run["run_id"],
        organization_id=organization,
    )

    async def always_fails(envelope, *, settings, owner):
        raise RuntimeError("ticket body 4111111111111111 would be unsafe to record")

    monkeypatch.setattr(consumer_module, "dispatch", always_fails)
    worker = StreamConsumer(settings, redis=worker_client, owner="worker-1")
    await worker.ensure_group()
    for _ in range(settings.worker_max_attempts + 2):
        await worker.process_once()

    with db_connection.cursor() as cursor:
        cursor.execute(
            "SELECT status, last_error_code, lease_owner FROM agent_runs WHERE id = %s",
            (seeded_run["run_id"],),
        )
        status, error_code, lease_owner = cursor.fetchone()

    assert status == "FAILED"
    # A code, not the exception's words — which in this test deliberately contain something that
    # must never reach a column or a log.
    assert error_code == MAX_ATTEMPTS_ERROR_CODE
    assert "4111111111111111" not in (error_code or "")
    # The lease is cleared, so nothing is left holding a dead run.
    assert lease_owner is None


async def test_exhausting_attempts_never_rewrites_a_run_that_already_finished(
    db_connection, organization, worker_client, settings, seeded_run, monkeypatch
):
    # A duplicate that runs out of attempts must not turn a COMPLETED run into a FAILED one.
    with db_connection.cursor() as cursor:
        cursor.execute(
            "UPDATE agent_runs SET status = 'COMPLETED' WHERE id = %s", (seeded_run["run_id"],)
        )
    db_connection.commit()
    await _publish(
        worker_client,
        settings,
        event_type=AGENT_RUN_REQUESTED,
        aggregate_id=seeded_run["run_id"],
        organization_id=organization,
    )

    async def always_fails(envelope, *, settings, owner):
        raise RuntimeError("transient")

    monkeypatch.setattr(consumer_module, "dispatch", always_fails)
    worker = StreamConsumer(settings, redis=worker_client, owner="worker-1")
    await worker.ensure_group()
    for _ in range(settings.worker_max_attempts + 2):
        await worker.process_once()

    with db_connection.cursor() as cursor:
        cursor.execute(
            "SELECT status, last_error_code FROM agent_runs WHERE id = %s", (seeded_run["run_id"],)
        )
        assert cursor.fetchone() == ("COMPLETED", None)


async def test_a_crash_after_the_action_but_before_the_ack_performs_it_once(
    db_connection, organization, worker_client, settings, seeded_run, monkeypatch
):
    """T26's acceptance wording, from the worker's side.

    The approved action is performed and committed, then the process dies before `XACK`. The entry
    is reclaimed and the resume runs again — and the action must not be performed a second time.
    Two independent guards have to hold for that: the run is no longer `AWAITING_APPROVAL`, so
    dispatch stops before the graph; and even if it did not, the API refuses a second effect for
    the same `(runId, toolCallId)`.
    """
    api = RecordingApi()

    # Start the run so it pauses on a human.
    await _publish(
        worker_client,
        settings,
        event_type=AGENT_RUN_REQUESTED,
        aggregate_id=seeded_run["run_id"],
        organization_id=organization,
    )
    worker = StreamConsumer(settings, redis=worker_client, owner="worker-1")
    await worker.ensure_group()
    monkeypatch.setattr(consumer_module, "dispatch", _dispatch_with(api))
    await worker.process_once()

    with db_connection.cursor() as cursor:
        cursor.execute("SELECT id FROM approvals WHERE run_id = %s", (seeded_run["run_id"],))
        approval_id = cursor.fetchone()[0]
        cursor.execute(
            "UPDATE approvals SET status = 'APPROVED', decision_by = %s, decision_at = now() "
            "WHERE id = %s",
            (seeded_run["user_id"], approval_id),
        )
    db_connection.commit()

    await _publish(
        worker_client,
        settings,
        event_type=AGENT_RUN_RESUME,
        aggregate_id=seeded_run["run_id"],
        organization_id=organization,
    )

    real_xack = worker_client.xack

    async def die_before_acking(*args, **kwargs):
        raise RedisError("process died before acknowledging")

    monkeypatch.setattr(worker_client, "xack", die_before_acking)
    with pytest.raises(RedisError):
        await worker.process_once()

    # The action happened; the acknowledgement did not.
    assert len(api.calls) == 1
    assert await _pending(worker_client, settings) == 1

    monkeypatch.setattr(worker_client, "xack", real_xack)
    await worker.process_once()

    assert await _pending(worker_client, settings) == 0
    # Still one. The redelivery found a run that is no longer awaiting approval.
    assert len(api.calls) == 1
