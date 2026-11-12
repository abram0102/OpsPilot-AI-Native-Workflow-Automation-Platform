"""Human-in-the-loop pause and resume, against a real database (docs/TASKS.md T23).

Covers the acceptance line: the pause persists across a worker restart, approving
resumes and invokes the action exactly once, rejecting invokes it zero times, a
malformed resume is refused, and nothing publishes without a decision.

**What these cannot prove.** Nothing in this repository runs `services/api` and
`services/agent` together, so the internal endpoint is stood in for by
`httpx.MockTransport`. "Posts exactly one *ticket message*" is therefore proven in
two halves: here, that exactly one internal call is made with the right payload;
and in Java (`InternalAgentActionHttpIntegrationTest`), that such a call posts
exactly one message and a replay posts none. The seam between them — that this
client's real request is accepted by that real endpoint — stays unproven, and a
mocked transport cannot catch a field-name or audience mismatch. See
docs/DECISIONS.md ADR-047.
"""

from __future__ import annotations

import json
from urllib.parse import urlparse
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr, ValidationError

from opspilot_agent.clients.internal_actions import InternalActionClient
from opspilot_agent.config import Settings
from opspilot_agent.graphs.approvals import ApprovalStatus, read_approval
from opspilot_agent.graphs.handler import execute_run, resume_run
from tests.conftest import insert_run, insert_ticket, insert_user

# docs/TASKS.md T28: part of the security regression suite (`make test-security`) —
# approval forgery and duplicate execution on resume.
pytestmark = [pytest.mark.db, pytest.mark.security]

SECRET = "a-local-test-secret-long-enough-for-hs256"


class RecordingApi:
    """Stands in for services/api's internal endpoint, recording every call it receives."""

    def __init__(self, status: str = "SUCCEEDED") -> None:
        self.calls: list[dict] = []
        self._status = status

    def client(self, settings: Settings) -> InternalActionClient:
        def handler(request: httpx.Request) -> httpx.Response:
            body = json.loads(request.content)
            self.calls.append(body)
            return httpx.Response(
                200,
                json={
                    "toolCallId": body["toolCallId"],
                    "status": self._status,
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
def settings(migrated_database: str) -> Settings:
    parsed = urlparse(migrated_database)
    return Settings(
        postgres_host=parsed.hostname or "localhost",
        postgres_port=parsed.port or 5432,
        postgres_db=(parsed.path or "/opspilot").lstrip("/"),
        postgres_user=parsed.username or "opspilot",
        postgres_password=parsed.password,
        internal_service_signing_secret=SecretStr(SECRET),
    )


@pytest.fixture
def seeded(db_connection, organization):
    user_id = insert_user(db_connection, f"{uuid4()}@example.test")
    ticket_id = insert_ticket(
        db_connection,
        organization_id=organization,
        created_by=user_id,
        subject="Where is my delivery?",
        description="My parcel has not arrived and tracking has not updated.",
    )
    run_id = insert_run(
        db_connection,
        organization_id=organization,
        ticket_id=ticket_id,
        initiated_by=user_id,
    )
    return {
        "organization_id": organization,
        "user_id": user_id,
        "ticket_id": ticket_id,
        "run_id": run_id,
    }


async def start_run(settings: Settings, seeded, api: RecordingApi):
    return await execute_run(
        organization_id=seeded["organization_id"],
        run_id=seeded["run_id"],
        ticket_id=seeded["ticket_id"],
        settings=settings,
        internal_actions=api.client(settings),
    )


def pending_approval_id(db_connection, seeded):
    with db_connection.cursor() as cursor:
        cursor.execute("SELECT id FROM approvals WHERE run_id = %s", (seeded["run_id"],))
        row = cursor.fetchone()
    return row[0] if row else None


def run_status(db_connection, run_id) -> str:
    with db_connection.cursor() as cursor:
        cursor.execute("SELECT status FROM agent_runs WHERE id = %s", (run_id,))
        return cursor.fetchone()[0]


class TestPausing:
    async def test_a_publish_proposal_pauses_the_run_and_records_an_approval(
        self, db_connection, settings, seeded
    ) -> None:
        api = RecordingApi()

        outcome = await start_run(settings, seeded, api)

        assert outcome.status == "AWAITING_APPROVAL"
        assert run_status(db_connection, seeded["run_id"]) == "AWAITING_APPROVAL"
        # Nothing was published: the whole point of the pause.
        assert api.calls == []

        approval_id = pending_approval_id(db_connection, seeded)
        assert approval_id is not None
        approval = read_approval(
            db_connection,
            organization_id=seeded["organization_id"],
            approval_id=approval_id,
        )
        assert approval.status is ApprovalStatus.PENDING
        assert approval.proposal["body"]

    async def test_the_approval_carries_the_draft_and_its_citations(
        self, db_connection, settings, seeded
    ) -> None:
        await start_run(settings, seeded, RecordingApi())

        approval = read_approval(
            db_connection,
            organization_id=seeded["organization_id"],
            approval_id=pending_approval_id(db_connection, seeded),
        )

        # T22's detail view reads `citations` as its evidence list.
        assert "citations" in approval.proposal
        assert isinstance(approval.proposal["citations"], list)

    async def test_the_pause_survives_a_fresh_worker_process(
        self, db_connection, settings, seeded
    ) -> None:
        """ "Pause persists through worker process stop/start."

        Each call builds a brand-new graph and checkpointer from scratch — nothing is carried in
        memory between them, which is what makes the thread id in Postgres the only link.
        """
        await start_run(settings, seeded, RecordingApi())
        approval_id = pending_approval_id(db_connection, seeded)

        # The human's decision, as T22's endpoint records it. Without this the run would publish
        # nothing — which the no-bypass test covers, and which caught the first version of this
        # test for not approving anything.
        with db_connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE approvals SET status = 'APPROVED', decision_by = %s, decision_at = now()
                 WHERE id = %s
                """,
                (seeded["user_id"], approval_id),
            )
        db_connection.commit()

        # A completely separate "process": new client, new graph, new checkpointer.
        api = RecordingApi()
        outcome = await resume_run(
            organization_id=seeded["organization_id"],
            run_id=seeded["run_id"],
            approval_id=approval_id,
            decision="APPROVED",
            settings=settings,
            internal_actions=api.client(settings),
        )

        assert outcome.status == "COMPLETED"
        assert len(api.calls) == 1


class TestApproving:
    async def _approve_and_resume(self, db_connection, settings, seeded, api: RecordingApi):
        await start_run(settings, seeded, RecordingApi())
        approval_id = pending_approval_id(db_connection, seeded)

        # The human's decision, as T22's endpoint would have recorded it.
        with db_connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE approvals
                   SET status = 'APPROVED', decision_by = %s, decision_at = now()
                 WHERE id = %s
                """,
                (seeded["user_id"], approval_id),
            )
        db_connection.commit()

        outcome = await resume_run(
            organization_id=seeded["organization_id"],
            run_id=seeded["run_id"],
            approval_id=approval_id,
            decision="APPROVED",
            settings=settings,
            internal_actions=api.client(settings),
        )
        return approval_id, outcome

    async def test_approving_invokes_the_action_exactly_once(
        self, db_connection, settings, seeded
    ) -> None:
        api = RecordingApi()

        approval_id, outcome = await self._approve_and_resume(db_connection, settings, seeded, api)

        assert outcome.status == "COMPLETED"
        assert len(api.calls) == 1
        call = api.calls[0]
        assert call["actionType"] == "PUBLISH_INTERNAL_REPLY"
        # The body is not sent: the API takes the text from the immutable stored proposal.
        assert call["arguments"] == {"approvalId": str(approval_id)}
        assert call["runId"] == str(seeded["run_id"])

    async def test_the_run_records_what_was_published(
        self, db_connection, settings, seeded
    ) -> None:
        _, outcome = await self._approve_and_resume(db_connection, settings, seeded, RecordingApi())

        assert outcome.published_reference is not None
        assert run_status(db_connection, seeded["run_id"]) == "COMPLETED"

    async def test_a_second_resume_does_not_invoke_the_action_again(
        self, db_connection, settings, seeded
    ) -> None:
        """At-least-once delivery means this will happen; it must not publish twice."""
        api = RecordingApi()
        approval_id, _ = await self._approve_and_resume(db_connection, settings, seeded, api)
        assert len(api.calls) == 1

        second = RecordingApi()
        await resume_run(
            organization_id=seeded["organization_id"],
            run_id=seeded["run_id"],
            approval_id=approval_id,
            decision="APPROVED",
            settings=settings,
            internal_actions=second.client(settings),
        )

        # The interrupt is already answered, so the graph has nothing left to resume.
        assert second.calls == []


class TestRejecting:
    async def test_rejecting_invokes_the_action_zero_times(
        self, db_connection, settings, seeded
    ) -> None:
        await start_run(settings, seeded, RecordingApi())
        approval_id = pending_approval_id(db_connection, seeded)

        with db_connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE approvals
                   SET status = 'REJECTED', decision_by = %s, decision_at = now()
                 WHERE id = %s
                """,
                (seeded["user_id"], approval_id),
            )
        db_connection.commit()

        api = RecordingApi()
        outcome = await resume_run(
            organization_id=seeded["organization_id"],
            run_id=seeded["run_id"],
            approval_id=approval_id,
            decision="REJECTED",
            settings=settings,
            internal_actions=api.client(settings),
        )

        # Nothing published, and the run finishes rather than hanging — a rejection is the run
        # doing its job (ADR-041).
        assert api.calls == []
        assert outcome.status == "COMPLETED"
        assert run_status(db_connection, seeded["run_id"]) == "COMPLETED"


class TestNoBypass:
    async def test_a_resume_claiming_approval_cannot_publish_a_pending_approval(
        self, db_connection, settings, seeded
    ) -> None:
        """ "No automatic publishing bypass."

        The resume payload says APPROVED while the database still says PENDING. The database
        wins — the claim is a hint, exactly as the worker's claims are to the API (ADR-045).
        """
        await start_run(settings, seeded, RecordingApi())
        approval_id = pending_approval_id(db_connection, seeded)

        api = RecordingApi()
        await resume_run(
            organization_id=seeded["organization_id"],
            run_id=seeded["run_id"],
            approval_id=approval_id,
            decision="APPROVED",
            settings=settings,
            internal_actions=api.client(settings),
        )

        assert api.calls == []

    async def test_a_resume_naming_another_runs_approval_is_refused(
        self, db_connection, settings, seeded, other_organization
    ) -> None:
        await start_run(settings, seeded, RecordingApi())

        other_user = insert_user(db_connection, f"{uuid4()}@example.test")
        other_ticket = insert_ticket(
            db_connection, organization_id=other_organization, created_by=other_user
        )
        other_run = insert_run(
            db_connection,
            organization_id=other_organization,
            ticket_id=other_ticket,
            initiated_by=other_user,
        )
        with db_connection.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO approvals (id, organization_id, run_id, ticket_id, tool_call_id,
                                       action_type, proposal, status, decision_by, decision_at)
                VALUES (%s, %s, %s, %s, 'other-call', 'PUBLISH_INTERNAL_REPLY',
                        '{"body":"Someone else''s reply."}', 'APPROVED', %s, now())
                """,
                (uuid4(), other_organization, other_run, other_ticket, other_user),
            )
        db_connection.commit()
        with db_connection.cursor() as cursor:
            cursor.execute("SELECT id FROM approvals WHERE tool_call_id = 'other-call'")
            foreign_approval = cursor.fetchone()[0]

        api = RecordingApi()
        outcome = await resume_run(
            organization_id=seeded["organization_id"],
            run_id=seeded["run_id"],
            approval_id=foreign_approval,
            decision="APPROVED",
            settings=settings,
            internal_actions=api.client(settings),
        )

        # Refused outright: the resume names an approval this run never raised, so nothing is
        # published and the run fails visibly rather than quietly doing nothing.
        assert api.calls == []
        assert outcome.status == "FAILED"
        assert outcome.error_code == "APPROVAL_RESUME_MISMATCH"


class TestMalformedResume:
    async def test_a_resume_missing_its_decision_is_refused(
        self, db_connection, settings, seeded
    ) -> None:
        """LangGraph validates the resume against `ResumeDecision` and saves nothing when invalid,
        so the pause survives and can be answered again."""
        await start_run(settings, seeded, RecordingApi())

        api = RecordingApi()
        with pytest.raises(ValidationError):
            await resume_run(
                organization_id=seeded["organization_id"],
                run_id=seeded["run_id"],
                approval_id=pending_approval_id(db_connection, seeded),
                decision="NOT_A_REAL_DECISION",
                settings=settings,
                internal_actions=api.client(settings),
            )

        assert api.calls == []

    async def test_the_pause_can_still_be_answered_after_a_malformed_resume(
        self, db_connection, settings, seeded
    ) -> None:
        await start_run(settings, seeded, RecordingApi())
        approval_id = pending_approval_id(db_connection, seeded)

        with pytest.raises(ValidationError):
            await resume_run(
                organization_id=seeded["organization_id"],
                run_id=seeded["run_id"],
                approval_id=approval_id,
                decision="GARBAGE",
                settings=settings,
                internal_actions=RecordingApi().client(settings),
            )

        with db_connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE approvals SET status = 'APPROVED', decision_by = %s, decision_at = now()
                 WHERE id = %s
                """,
                (seeded["user_id"], approval_id),
            )
        db_connection.commit()

        api = RecordingApi()
        outcome = await resume_run(
            organization_id=seeded["organization_id"],
            run_id=seeded["run_id"],
            approval_id=approval_id,
            decision="APPROVED",
            settings=settings,
            internal_actions=api.client(settings),
        )

        assert outcome.status == "COMPLETED"
        assert len(api.calls) == 1
