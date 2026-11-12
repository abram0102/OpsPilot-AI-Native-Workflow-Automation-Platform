"""Shared fixtures, including the real-Postgres fixture the `db` tests use.

Tests marked `db` need PostgreSQL with pgvector. This development environment has
no Docker (Gate H0, docs/PROGRESS.md T01), so they skip here and run in CI, where
the agent job has a `pgvector/pgvector:pg16` service container. CI additionally
runs them as their own step, so they cannot silently skip there — a skip would
mean zero tests collected, which fails that step.

Schema comes from the real Flyway migration files in services/api rather than a
copy maintained here: Flyway owns the schema (ADR-007), and a second definition
would drift from it without anyone noticing.
"""

from __future__ import annotations

import os
from collections.abc import Iterator
from pathlib import Path
from uuid import UUID, uuid4

import pytest

MIGRATIONS_DIR = (
    Path(__file__).resolve().parents[3]
    / "services"
    / "api"
    / "src"
    / "main"
    / "resources"
    / "db"
    / "migration"
)


@pytest.fixture(autouse=True)
def _hermetic_settings_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    """Hide the developer's shell from `Settings`.

    `pydantic_settings` reads the real environment for every field a test does not pass
    explicitly, so running the suite in a shell that has sourced `.env` — which is exactly what
    `make worker-dev` and `make api-dev` ask you to do — made three tests fail: they assert that
    real mode WITHOUT a model id is refused, and the shell was supplying one. CI never exports
    `.env`, so CI stayed green while the suite was broken for anyone running it locally.

    The test-only DSNs are kept: they are read by the fixtures below, not by `Settings`, and
    clearing them would skip every database- and Redis-backed test. A test that wants a variable
    sets it with `monkeypatch.setenv`, which runs after this fixture.
    """
    keep = {"POSTGRES_TEST_DSN", "REDIS_TEST_URL"}
    for name in list(os.environ):
        if name in keep:
            continue
        if name.startswith(
            (
                "AI_",
                "LLM_",
                "AGENT_",
                "EMBEDDING_",
                "WORKER_",
                "REDIS_",
                "POSTGRES_",
                "INTERNAL_SERVICE_",
                "API_",
                "EVAL_",
                "ALLOW_REAL_",
                "RETRIEVAL_",
                "STORAGE_",
                "S3_",
            )
        ):
            monkeypatch.delenv(name, raising=False)


def _test_dsn() -> str | None:
    return os.environ.get("POSTGRES_TEST_DSN")


@pytest.fixture(scope="session")
def postgres_dsn() -> str:
    dsn = _test_dsn()
    if dsn:
        return dsn

    # Skipping is only legitimate on a machine that cannot run a database. In CI the container is
    # configured, so a missing DSN is a broken workflow, not an environment limitation — and a
    # skipped test there would pass while proving nothing. (Selecting `-m db` alone does not catch
    # this: pytest exits 0 when every selected test skips.)
    if os.environ.get("CI"):
        pytest.fail(
            "POSTGRES_TEST_DSN is not set, but this is CI, where the pgvector service container "
            "should have provided it. Refusing to skip the database-backed tests.",
            pytrace=False,
        )

    pytest.skip(
        "POSTGRES_TEST_DSN is not set — these tests need PostgreSQL with pgvector "
        "(Gate H0: no Docker in this environment; CI runs them against a service container)."
    )


@pytest.fixture(scope="session")
def migrated_database(postgres_dsn: str) -> str:
    """Applies every Flyway migration once per session, against a clean schema."""
    import psycopg

    migrations = sorted(MIGRATIONS_DIR.glob("V*.sql"))
    assert migrations, f"No migrations found under {MIGRATIONS_DIR}"

    with psycopg.connect(postgres_dsn) as connection:
        with connection.cursor() as cursor:
            # Start from nothing so a reused database cannot mask a missing migration.
            cursor.execute("DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;")
            for migration in migrations:
                cursor.execute(migration.read_text(encoding="utf-8"))
        connection.commit()

    return postgres_dsn


@pytest.fixture
def db_connection(migrated_database: str) -> Iterator[object]:
    """A connection with the data tables emptied, so tests cannot leak into each other."""
    import psycopg

    with psycopg.connect(migrated_database) as connection:
        with connection.cursor() as cursor:
            # Everything a test can create, or rows leak between tests. `organizations` and
            # `users` cascade to most of the rest, but naming each table keeps a new one from
            # being silently forgotten the way the agent tables were when T16 wrote this list.
            cursor.execute(
                "TRUNCATE agent_usage, tool_executions, approvals, agent_steps, agent_runs, "
                "ticket_tags, ticket_messages, tickets, customers, knowledge_chunks, "
                "knowledge_documents, memberships, organizations, users "
                "RESTART IDENTITY CASCADE"
            )
        connection.commit()
        yield connection


@pytest.fixture
def organization(db_connection) -> UUID:
    return _insert_organization(db_connection, "Acme", "acme")


@pytest.fixture
def other_organization(db_connection) -> UUID:
    return _insert_organization(db_connection, "Globex", "globex")


def _insert_organization(connection, name: str, slug: str) -> UUID:
    # `organizations.created_by` is NOT NULL and references `users`, so an organization cannot
    # exist without its creator — every org fixture makes one.
    creator_id = insert_user(connection, f"creator-{slug}@example.test")
    org_id = uuid4()
    with connection.cursor() as cursor:
        cursor.execute(
            "INSERT INTO organizations (id, name, slug, created_by) VALUES (%s, %s, %s, %s)",
            (org_id, name, slug, creator_id),
        )
    connection.commit()
    return org_id


def insert_user(connection, email: str = "owner@example.test") -> UUID:
    user_id = uuid4()
    with connection.cursor() as cursor:
        cursor.execute(
            "INSERT INTO users (id, auth_subject, email, display_name) VALUES (%s, %s, %s, %s)",
            (user_id, f"test|{user_id}", email, "Test Owner"),
        )
    connection.commit()
    return user_id


def insert_customer(connection, *, organization_id: UUID, name: str = "Ada") -> UUID:
    customer_id = uuid4()
    with connection.cursor() as cursor:
        cursor.execute(
            "INSERT INTO customers (id, organization_id, display_name, email) VALUES (%s,%s,%s,%s)",
            (customer_id, organization_id, name, f"{customer_id}@example.test"),
        )
    connection.commit()
    return customer_id


def insert_ticket(
    connection,
    *,
    organization_id: UUID,
    created_by: UUID,
    subject: str = "Where is my order?",
    description: str = "It has not arrived.",
    priority: str = "MEDIUM",
) -> UUID:
    customer_id = insert_customer(connection, organization_id=organization_id)
    ticket_id = uuid4()
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO tickets (id, organization_id, customer_id, subject, description,
                                 priority, created_by)
            VALUES (%s, %s, %s, %s, %s, %s, %s)
            """,
            (
                ticket_id,
                organization_id,
                customer_id,
                subject,
                description,
                priority,
                created_by,
            ),
        )
    connection.commit()
    return ticket_id


def insert_run(
    connection,
    *,
    organization_id: UUID,
    ticket_id: UUID,
    initiated_by: UUID,
    status: str = "QUEUED",
) -> UUID:
    run_id = uuid4()
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO agent_runs (id, organization_id, ticket_id, initiated_by, status, workflow)
            VALUES (%s, %s, %s, %s, %s, 'SUPPORT_TRIAGE_V1')
            """,
            (run_id, organization_id, ticket_id, initiated_by, status),
        )
    connection.commit()
    return run_id


def insert_document(
    connection,
    *,
    organization_id: UUID,
    uploaded_by: UUID,
    filename: str,
    object_key: str,
    mime_type: str = "text/markdown",
    status: str = "QUEUED",
    size_bytes: int = 1024,
    sha256: str = "0" * 64,
) -> UUID:
    document_id = uuid4()
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO knowledge_documents
                   (id, organization_id, uploaded_by, filename, mime_type, object_key,
                    sha256, size_bytes, status)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
            """,
            (
                document_id,
                organization_id,
                uploaded_by,
                filename,
                mime_type,
                object_key,
                sha256,
                size_bytes,
                status,
            ),
        )
    connection.commit()
    return document_id


# --- Redis (T25) -----------------------------------------------------------------------------
#
# Same shape as the Postgres fixtures above, and for the same reason: a skipped test in CI would
# pass while proving nothing about XREADGROUP, XAUTOCLAIM or acknowledgement ordering, which are
# exactly the things that cannot be reasoned about without a real server.
#
# Two settings fixtures, not one. Plenty of the consumer's behaviour — group creation, parsing,
# dead-lettering, acknowledgement order, reclaim — needs only Redis, and making those tests drag
# in a database would mean a machine with Redis and no Postgres could not run any of them.


def _redis_url() -> str | None:
    return os.environ.get("REDIS_TEST_URL")


@pytest.fixture(scope="session")
def redis_url() -> str:
    url = _redis_url()
    if url:
        return url

    if os.environ.get("CI"):
        pytest.fail(
            "REDIS_TEST_URL is not set, but this is CI, where the redis service container "
            "should have provided it. Refusing to skip the Redis-backed tests.",
            pytrace=False,
        )

    pytest.skip(
        "REDIS_TEST_URL is not set — these tests need a real Redis "
        "(run one locally, or let CI's service container provide it)."
    )


def _settings_for(redis_url: str, postgres_dsn: str | None):
    """Settings with timings compressed for tests.

    The production defaults (a 60s lease, a 60s reclaim window, a 5s block) would make every
    reclaim test take a minute. Shortened here — and only here — so the mechanism is exercised
    rather than waited on; the shipped defaults are asserted in test_config.py.
    """
    from urllib.parse import urlparse

    from opspilot_agent.config import Settings

    redis_parts = urlparse(redis_url)
    overrides = {}
    if postgres_dsn:
        db = urlparse(postgres_dsn)
        overrides = {
            "postgres_host": db.hostname or "localhost",
            "postgres_port": db.port or 5432,
            "postgres_db": (db.path or "/opspilot").lstrip("/"),
            "postgres_user": db.username or "opspilot",
            "postgres_password": db.password,
        }

    return Settings(
        redis_host=redis_parts.hostname or "localhost",
        redis_port=redis_parts.port or 6379,
        # A unique stream per test, so tests cannot see each other's messages even when they run
        # against a shared Redis.
        redis_stream_name=f"test:events:{uuid4()}",
        redis_dlq_stream_name=f"test:events:dlq:{uuid4()}",
        redis_block_ms=50,
        worker_lease_seconds=5,
        worker_heartbeat_seconds=0.05,
        # Reclaim immediately: every pending entry is "idle enough" the moment it is pending.
        worker_reclaim_min_idle_ms=0,
        worker_backoff_base_seconds=0.0,
        worker_backoff_max_seconds=0.0,
        worker_max_attempts=3,
        **overrides,
    )


@pytest.fixture
def redis_settings(redis_url: str):
    """Redis only — for the consumer mechanics that never touch a database."""
    return _settings_for(redis_url, None)


@pytest.fixture
def worker_settings(redis_url: str, migrated_database: str):
    """Redis *and* Postgres — for end-to-end dispatch through a real handler."""
    return _settings_for(redis_url, migrated_database)


@pytest.fixture
async def redis_client(redis_settings):
    """A client whose test keys are removed afterwards, so nothing leaks between tests."""
    async for client in _client(redis_settings):
        yield client


@pytest.fixture
async def worker_client(worker_settings):
    async for client in _client(worker_settings):
        yield client


async def _client(settings):
    from opspilot_agent.streams.client import build_redis

    client = build_redis(settings)
    try:
        yield client
    finally:
        await client.delete(settings.redis_stream_name, settings.redis_dlq_stream_name)
        await client.aclose()
