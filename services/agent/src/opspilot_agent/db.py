"""Database access for the worker.

The worker reads and writes `knowledge_documents`/`knowledge_chunks` directly:
docs/PRODUCT_AND_ARCHITECTURE.md assigns indexing and "tenant-safe vector
search" to this service. That is narrower than it sounds — the worker's
*business actions* on tickets still go through the Java internal API (T21), and
nothing here touches tickets, approvals or runs.

Flyway (in services/api) owns the schema. Nothing in this service creates or
alters a table.
"""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager

import psycopg

from opspilot_agent.config import Settings


def build_dsn(settings: Settings) -> str:
    """A libpq connection string from the same env vars the rest of the stack uses."""
    password = settings.postgres_password.get_secret_value() if settings.postgres_password else None
    parts = [
        f"host={settings.postgres_host}",
        f"port={settings.postgres_port}",
        f"dbname={settings.postgres_db}",
        f"user={settings.postgres_user}",
    ]
    if password:
        parts.append(f"password={password}")
    return " ".join(parts)


@contextmanager
def connect(settings: Settings) -> Iterator[psycopg.Connection]:
    """A single connection, committed on success and rolled back on error.

    Deliberately not a pool: the only caller today is a one-document-at-a-time ingestion handler
    driven by a CLI. The Redis consumer in T25 is where concurrency — and therefore pooling —
    actually becomes a question worth answering with measurements.
    """
    with psycopg.connect(build_dsn(settings)) as connection:
        yield connection


def to_vector_literal(vector: list[float]) -> str:
    """Formats an embedding the way pgvector parses it.

    pgvector accepts its own text representation (`[1,2,3]`), so passing the literal as a
    parameter and casting it in SQL avoids depending on `pgvector.psycopg` for this one need.
    """
    return "[" + ",".join(repr(float(value)) for value in vector) + "]"
