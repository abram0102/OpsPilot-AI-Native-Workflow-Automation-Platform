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
