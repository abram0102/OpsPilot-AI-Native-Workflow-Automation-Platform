"""T25: what one envelope actually does, against a real database.

The envelope carries identifiers only, so almost everything interesting here is about *reloading
trusted state*: which run this is, what status it is in, which approval was decided, and whether
this worker is allowed to touch it. No Redis — the loop around this is tested separately in
`test_stream_consumer_redis.py`.

The acceptance line these cover most directly: "replay of completed run results in zero duplicate
side effect". That is settled before any graph starts, by reading the run's status.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime
from urllib.parse import urlparse
from uuid import UUID, uuid4

import httpx
import pytest

from opspilot_agent.clients.internal_actions import InternalActionClient
from opspilot_agent.config import Settings
from opspilot_agent.knowledge.storage import LocalObjectStore
from opspilot_agent.streams.dispatch import (
    EventRejected,
    LeaseUnavailable,
    dispatch,
)
from opspilot_agent.streams.envelope import (
    AGENT_RUN_REQUESTED,
    AGENT_RUN_RESUME,
    KNOWLEDGE_INGEST_REQUESTED,
    SCHEMA_VERSION,
    Envelope,
)
from opspilot_agent.streams.leases import acquire_run_lease
from tests.conftest import insert_document, insert_run, insert_ticket, insert_user

pytestmark = pytest.mark.db

SECRET = "a-local-test-secret-long-enough-for-hs256"
FAQ_TEXT = b"""# Shipping FAQ

Standard delivery takes three to five working days. A parcel counts as lost after ten working
days without a scan, and a replacement is dispatched at no cost.
"""


class RecordingApi:
    """Stands in for services/api's internal action endpoint, recording what it receives."""

    def __init__(self) -> None:
        self.calls: list[dict] = []

    def client(self, settings: Settings) -> InternalActionClient:
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


@pytest.fixture
def settings(migrated_database: str, tmp_path) -> Settings:
    parsed = urlparse(migrated_database)
    return Settings(
        postgres_host=parsed.hostname or "localhost",
        postgres_port=parsed.port or 5432,
        postgres_db=(parsed.path or "/opspilot").lstrip("/"),
        postgres_user=parsed.username or "opspilot",
        postgres_password=parsed.password,
        storage_local_dir=str(tmp_path),
        internal_service_signing_secret=SECRET,
        worker_lease_seconds=60,
        worker_heartbeat_seconds=30.0,
    )


def _envelope(event_type: str, aggregate_id: UUID, organization_id: UUID) -> Envelope:
    return Envelope(
        event_id=uuid4(),
        type=event_type,
        aggregate_id=aggregate_id,
        organization_id=organization_id,
        schema_version=SCHEMA_VERSION,
        created_at=datetime.now(UTC),
    )


def _run_row(connection, run_id):
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT status, lease_owner, last_error_code FROM agent_runs WHERE id = %s", (run_id,)
        )
        return cursor.fetchone()


def _chunk_count(connection, document_id) -> int:
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT count(*) FROM knowledge_chunks WHERE document_id = %s", (document_id,)
        )
        return cursor.fetchone()[0]


@pytest.fixture
def seeded(db_connection, organization, tmp_path):
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


def _store_document(db_connection, organization, tmp_path, user_id) -> UUID:
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


# --- knowledge.ingest.requested -----------------------------------------------------------


class TestIngestEvents:
    async def test_an_ingest_event_indexes_the_document_it_names(
        self, db_connection, organization, settings, seeded, tmp_path
    ):
        document_id = _store_document(db_connection, organization, tmp_path, seeded["user_id"])

        result = await dispatch(
            _envelope(KNOWLEDGE_INGEST_REQUESTED, document_id, organization),
            settings=settings,
            owner="worker-1",
        )

        assert result.executed is True
        assert result.status == "READY"
        assert _chunk_count(db_connection, document_id) > 0

    async def test_a_redelivered_ingest_event_does_not_duplicate_chunks(
        self, db_connection, organization, settings, seeded, tmp_path
    ):
        """At-least-once, absorbed by the indexer's own uniqueness (T16), not by a dedup table."""
        document_id = _store_document(db_connection, organization, tmp_path, seeded["user_id"])
        envelope = _envelope(KNOWLEDGE_INGEST_REQUESTED, document_id, organization)

        await dispatch(envelope, settings=settings, owner="worker-1")
        first_count = _chunk_count(db_connection, document_id)
        # The same envelope again, exactly as a crash between XADD and ACK would produce.
        await dispatch(envelope, settings=settings, owner="worker-1")

        assert _chunk_count(db_connection, document_id) == first_count

    async def test_an_ingest_event_for_a_missing_document_is_rejected_not_retried(
        self, organization, settings
    ):
        with pytest.raises(EventRejected) as rejected:
            await dispatch(
                _envelope(KNOWLEDGE_INGEST_REQUESTED, uuid4(), organization),
                settings=settings,
                owner="worker-1",
            )

        assert rejected.value.code == "DOCUMENT_NOT_FOUND"

    async def test_another_tenants_document_is_indistinguishable_from_a_missing_one(
        self, db_connection, organization, other_organization, settings, seeded, tmp_path
    ):
        document_id = _store_document(db_connection, organization, tmp_path, seeded["user_id"])

        with pytest.raises(EventRejected) as rejected:
            await dispatch(
                # A forged envelope: the right document, the wrong tenant.
                _envelope(KNOWLEDGE_INGEST_REQUESTED, document_id, other_organization),
                settings=settings,
                owner="worker-1",
            )

        assert rejected.value.code == "DOCUMENT_NOT_FOUND"
        assert _chunk_count(db_connection, document_id) == 0


# --- agent.run.requested ---------------------------------------------------------------------


class TestRunRequestedEvents:
    async def test_a_run_event_runs_the_graph_and_releases_the_lease(
        self, db_connection, organization, settings, seeded
    ):
        result = await dispatch(
            _envelope(AGENT_RUN_REQUESTED, seeded["run_id"], organization),
            settings=settings,
            owner="worker-1",
            internal_actions=RecordingApi().client(settings),
        )

        assert result.executed is True
        # The triage graph proposes a reply, so the run parks on a human (T23).
        assert result.status == "AWAITING_APPROVAL"
        status, lease_owner, _ = _run_row(db_connection, seeded["run_id"])
        assert status == "AWAITING_APPROVAL"
        # Released on a clean finish, so a retry need not wait out the TTL.
        assert lease_owner is None

    @pytest.mark.parametrize("terminal", ["COMPLETED", "FAILED", "CANCELLED"])
    async def test_a_replayed_event_for_a_finished_run_does_nothing(
        self, db_connection, organization, settings, seeded, terminal
    ):
        """The acceptance criterion, decided before any graph starts."""
        with db_connection.cursor() as cursor:
            cursor.execute(
                "UPDATE agent_runs SET status = %s WHERE id = %s", (terminal, seeded["run_id"])
            )
        db_connection.commit()

        result = await dispatch(
            _envelope(AGENT_RUN_REQUESTED, seeded["run_id"], organization),
            settings=settings,
            owner="worker-1",
        )

        assert result.executed is False
        assert result.detail == "RUN_ALREADY_TERMINAL"
        # Untouched: no new steps, no status change, and no lease taken.
        status, lease_owner, _ = _run_row(db_connection, seeded["run_id"])
        assert status == terminal
        assert lease_owner is None
        with db_connection.cursor() as cursor:
            cursor.execute(
                "SELECT count(*) FROM agent_steps WHERE run_id = %s", (seeded["run_id"],)
            )
            assert cursor.fetchone()[0] == 0

    async def test_a_paused_run_is_not_restarted_from_the_top(
        self, db_connection, organization, settings, seeded
    ):
        # A redelivered *start* event for a run waiting on a human must not re-run the graph; the
        # only thing that moves it on is agent.run.resume.
        with db_connection.cursor() as cursor:
            cursor.execute(
                "UPDATE agent_runs SET status = 'AWAITING_APPROVAL' WHERE id = %s",
                (seeded["run_id"],),
            )
        db_connection.commit()

        result = await dispatch(
            _envelope(AGENT_RUN_REQUESTED, seeded["run_id"], organization),
            settings=settings,
            owner="worker-1",
        )

        assert result.executed is False
        assert result.detail == "RUN_AWAITING_APPROVAL"

    async def test_a_run_leased_by_another_worker_is_left_alone(
        self, db_connection, organization, settings, seeded
    ):
        acquire_run_lease(
            db_connection,
            organization_id=organization,
            run_id=seeded["run_id"],
            owner="worker-other",
            ttl_seconds=600,
        )

        with pytest.raises(LeaseUnavailable):
            await dispatch(
                _envelope(AGENT_RUN_REQUESTED, seeded["run_id"], organization),
                settings=settings,
                owner="worker-1",
            )

        # The holder keeps it; a refused attempt must not disturb the run.
        status, lease_owner, _ = _run_row(db_connection, seeded["run_id"])
        assert lease_owner == "worker-other"
        assert status == "QUEUED"

    async def test_an_unknown_run_is_rejected(self, organization, settings):
        with pytest.raises(EventRejected) as rejected:
            await dispatch(
                _envelope(AGENT_RUN_REQUESTED, uuid4(), organization),
                settings=settings,
                owner="worker-1",
            )

        assert rejected.value.code == "RUN_NOT_FOUND"

    async def test_another_tenants_run_is_rejected_the_same_way(
        self, organization, other_organization, settings, seeded
    ):
        with pytest.raises(EventRejected) as rejected:
            await dispatch(
                _envelope(AGENT_RUN_REQUESTED, seeded["run_id"], other_organization),
                settings=settings,
                owner="worker-1",
            )

        # Concealment, not a distinct error: another tenant's run must look exactly like no run.
        assert rejected.value.code == "RUN_NOT_FOUND"


# --- agent.run.resume ------------------------------------------------------------------------


class TestResumeEvents:
    async def _pause(self, db_connection, organization, settings, seeded) -> UUID:
        await dispatch(
            _envelope(AGENT_RUN_REQUESTED, seeded["run_id"], organization),
            settings=settings,
            owner="worker-1",
            internal_actions=RecordingApi().client(settings),
        )
        with db_connection.cursor() as cursor:
            cursor.execute("SELECT id FROM approvals WHERE run_id = %s", (seeded["run_id"],))
            return cursor.fetchone()[0]

    def _decide(self, db_connection, approval_id, decision, user_id):
        with db_connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE approvals
                   SET status = %s, decision_by = %s, decision_at = now()
                 WHERE id = %s
                """,
                (decision, user_id, approval_id),
            )
        db_connection.commit()

    async def test_a_resume_event_finds_the_decided_approval_itself(
        self, db_connection, organization, settings, seeded
    ):
        """Nothing on the wire says which approval was decided — the envelope names only the run."""
        approval_id = await self._pause(db_connection, organization, settings, seeded)
        self._decide(db_connection, approval_id, "APPROVED", seeded["user_id"])
        api = RecordingApi()

        result = await dispatch(
            _envelope(AGENT_RUN_RESUME, seeded["run_id"], organization),
            settings=settings,
            owner="worker-1",
            internal_actions=api.client(settings),
        )

        assert result.executed is True
        assert result.status == "COMPLETED"
        # The approved action was performed exactly once.
        assert len(api.calls) == 1
        status, lease_owner, _ = _run_row(db_connection, seeded["run_id"])
        assert status == "COMPLETED"
        assert lease_owner is None

    async def test_a_rejected_approval_resumes_without_performing_the_action(
        self, db_connection, organization, settings, seeded
    ):
        approval_id = await self._pause(db_connection, organization, settings, seeded)
        self._decide(db_connection, approval_id, "REJECTED", seeded["user_id"])
        api = RecordingApi()

        result = await dispatch(
            _envelope(AGENT_RUN_RESUME, seeded["run_id"], organization),
            settings=settings,
            owner="worker-1",
            internal_actions=api.client(settings),
        )

        assert result.executed is True
        # An AI draft is never published without a human's yes.
        assert api.calls == []

    async def test_a_redelivered_resume_does_not_perform_the_action_twice(
        self, db_connection, organization, settings, seeded
    ):
        approval_id = await self._pause(db_connection, organization, settings, seeded)
        self._decide(db_connection, approval_id, "APPROVED", seeded["user_id"])
        api = RecordingApi()
        envelope = _envelope(AGENT_RUN_RESUME, seeded["run_id"], organization)

        await dispatch(
            envelope, settings=settings, owner="worker-1", internal_actions=api.client(settings)
        )
        second = await dispatch(
            envelope, settings=settings, owner="worker-1", internal_actions=api.client(settings)
        )

        # The run is no longer awaiting approval, so the duplicate stops at the status check —
        # before the graph, and therefore before the action.
        assert second.executed is False
        assert second.detail == "RUN_NOT_AWAITING_APPROVAL"
        assert len(api.calls) == 1

    async def test_a_resume_for_a_run_that_never_paused_is_ignored(
        self, organization, settings, seeded
    ):
        result = await dispatch(
            _envelope(AGENT_RUN_RESUME, seeded["run_id"], organization),
            settings=settings,
            owner="worker-1",
        )

        assert result.executed is False
        assert result.detail == "RUN_NOT_AWAITING_APPROVAL"

    async def test_a_resume_with_no_decided_approval_is_rejected(
        self, db_connection, organization, settings, seeded
    ):
        # The approval is still PENDING: there is nothing for a resume to answer, and retrying
        # would not create one.
        await self._pause(db_connection, organization, settings, seeded)

        with pytest.raises(EventRejected) as rejected:
            await dispatch(
                _envelope(AGENT_RUN_RESUME, seeded["run_id"], organization),
                settings=settings,
                owner="worker-1",
            )

        assert rejected.value.code == "APPROVAL_NOT_DECIDED"
