"""T25: the per-run lease, against a real database.

The lease is the thing that stops two workers from running one graph. Everything it promises is a
property of a single conditional UPDATE, so these tests are all about which WHERE clauses hold:
ownership, expiry, and tenant scope. None of it can be checked without a database — an in-memory
double would just be a second implementation of the same SQL.
"""

from __future__ import annotations

import time
from uuid import uuid4

import pytest

from opspilot_agent.streams.leases import (
    acquire_run_lease,
    default_owner_name,
    release_run_lease,
    renew_run_lease,
)
from tests.conftest import insert_run, insert_ticket, insert_user

pytestmark = pytest.mark.db


@pytest.fixture
def run_id(db_connection, organization):
    user_id = insert_user(db_connection, "lease-owner@example.test")
    ticket_id = insert_ticket(db_connection, organization_id=organization, created_by=user_id)
    return insert_run(
        db_connection,
        organization_id=organization,
        ticket_id=ticket_id,
        initiated_by=user_id,
        status="QUEUED",
    )


def _lease_row(connection, run_id):
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT lease_owner, lease_expires_at FROM agent_runs WHERE id = %s", (run_id,)
        )
        return cursor.fetchone()


def test_an_unleased_run_can_be_leased(db_connection, organization, run_id):
    lease = acquire_run_lease(
        db_connection,
        organization_id=organization,
        run_id=run_id,
        owner="worker-1",
        ttl_seconds=60,
    )

    assert lease is not None
    assert lease.owner == "worker-1"
    owner, expires_at = _lease_row(db_connection, run_id)
    assert owner == "worker-1"
    assert expires_at is not None


def test_a_second_worker_cannot_take_a_live_lease(db_connection, organization, run_id):
    acquire_run_lease(
        db_connection,
        organization_id=organization,
        run_id=run_id,
        owner="worker-1",
        ttl_seconds=60,
    )

    assert (
        acquire_run_lease(
            db_connection,
            organization_id=organization,
            run_id=run_id,
            owner="worker-2",
            ttl_seconds=60,
        )
        is None
    )
    # And the first worker still holds it — a failed attempt must not disturb the holder.
    assert _lease_row(db_connection, run_id)[0] == "worker-1"


def test_the_holder_can_retake_its_own_lease(db_connection, organization, run_id):
    # A redelivery to the same worker is normal under at-least-once. Refusing it would strand the
    # run until the TTL expired, for no benefit.
    first = acquire_run_lease(
        db_connection,
        organization_id=organization,
        run_id=run_id,
        owner="worker-1",
        ttl_seconds=60,
    )
    second = acquire_run_lease(
        db_connection,
        organization_id=organization,
        run_id=run_id,
        owner="worker-1",
        ttl_seconds=60,
    )

    assert first is not None and second is not None
    assert second.expires_at >= first.expires_at


def test_an_expired_lease_can_be_taken_over(db_connection, organization, run_id):
    """Worker death, from the database's side: the lease simply ages out."""
    acquire_run_lease(
        db_connection,
        organization_id=organization,
        run_id=run_id,
        owner="worker-dead",
        ttl_seconds=1,
    )
    time.sleep(1.1)

    lease = acquire_run_lease(
        db_connection,
        organization_id=organization,
        run_id=run_id,
        owner="worker-survivor",
        ttl_seconds=60,
    )

    assert lease is not None
    assert lease.owner == "worker-survivor"


def test_a_lease_with_no_expiry_is_stealable_rather_than_eternal(
    db_connection, organization, run_id
):
    # An owner with a NULL expiry could never be reclaimed, so the run would be stranded for
    # good. Treated as abandoned instead.
    with db_connection.cursor() as cursor:
        cursor.execute(
            "UPDATE agent_runs SET lease_owner = 'ghost', lease_expires_at = NULL WHERE id = %s",
            (run_id,),
        )
    db_connection.commit()

    lease = acquire_run_lease(
        db_connection,
        organization_id=organization,
        run_id=run_id,
        owner="worker-1",
        ttl_seconds=60,
    )

    assert lease is not None
    assert lease.owner == "worker-1"


def test_a_run_in_another_tenant_cannot_be_leased(
    db_connection, organization, other_organization, run_id
):
    # organization_id is in the WHERE clause, so a cross-tenant run id is indistinguishable from
    # one that does not exist — the same concealment the HTTP layer uses.
    assert (
        acquire_run_lease(
            db_connection,
            organization_id=other_organization,
            run_id=run_id,
            owner="worker-1",
            ttl_seconds=60,
        )
        is None
    )
    assert _lease_row(db_connection, run_id)[0] is None


def test_a_run_that_does_not_exist_cannot_be_leased(db_connection, organization):
    assert (
        acquire_run_lease(
            db_connection,
            organization_id=organization,
            run_id=uuid4(),
            owner="worker-1",
            ttl_seconds=60,
        )
        is None
    )


def test_renewing_extends_our_own_lease(db_connection, organization, run_id):
    acquire_run_lease(
        db_connection,
        organization_id=organization,
        run_id=run_id,
        owner="worker-1",
        ttl_seconds=1,
    )
    _, before = _lease_row(db_connection, run_id)

    assert (
        renew_run_lease(
            db_connection,
            organization_id=organization,
            run_id=run_id,
            owner="worker-1",
            ttl_seconds=600,
        )
        is True
    )
    _, after = _lease_row(db_connection, run_id)
    assert after > before


def test_renewing_a_lease_we_no_longer_hold_fails(db_connection, organization, run_id):
    """The case that matters: a heartbeat must never silently re-take a reclaimed lease.

    If it did, the worker that was declared dead would go on believing it owned the run — which is
    exactly the double execution the lease exists to prevent.
    """
    acquire_run_lease(
        db_connection,
        organization_id=organization,
        run_id=run_id,
        owner="worker-new",
        ttl_seconds=600,
    )

    assert (
        renew_run_lease(
            db_connection,
            organization_id=organization,
            run_id=run_id,
            owner="worker-old",
            ttl_seconds=600,
        )
        is False
    )
    assert _lease_row(db_connection, run_id)[0] == "worker-new"


def test_releasing_frees_the_lease_for_an_immediate_retry(db_connection, organization, run_id):
    acquire_run_lease(
        db_connection,
        organization_id=organization,
        run_id=run_id,
        owner="worker-1",
        ttl_seconds=600,
    )

    assert (
        release_run_lease(
            db_connection, organization_id=organization, run_id=run_id, owner="worker-1"
        )
        is True
    )
    assert _lease_row(db_connection, run_id) == (None, None)


def test_a_worker_cannot_release_someone_elses_lease(db_connection, organization, run_id):
    # A worker that lost the lease mid-run must not clear the one the new owner is relying on.
    acquire_run_lease(
        db_connection,
        organization_id=organization,
        run_id=run_id,
        owner="worker-new",
        ttl_seconds=600,
    )

    assert (
        release_run_lease(
            db_connection, organization_id=organization, run_id=run_id, owner="worker-old"
        )
        is False
    )
    assert _lease_row(db_connection, run_id)[0] == "worker-new"


def test_the_default_owner_name_distinguishes_processes_on_one_machine(db_connection):
    from opspilot_agent.config import Settings

    name = default_owner_name(Settings())

    # host:pid — two workers on one machine must not share a name, because XAUTOCLAIM would then
    # treat each other's in-flight work as reclaimable.
    assert ":" in name
    assert name.rsplit(":", 1)[1].isdigit()
    # An explicit name wins, so a deployment can label its workers.
    assert default_owner_name(Settings(redis_consumer_name="worker-7")) == "worker-7"
