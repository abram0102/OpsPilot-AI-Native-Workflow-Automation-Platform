"""Turning a QUEUED knowledge document into searchable chunks.

The flow mirrors docs/PRODUCT_AND_ARCHITECTURE.md's ingest path: claim the
document, read its bytes, extract text, chunk it, embed the chunks, write
`knowledge_chunks` with the embedding model recorded, and mark the document
`READY` — or `FAILED` with a code a human can act on.

Three properties this is built around:

* **Idempotent.** Re-ingesting the same document must not duplicate chunks.
  `knowledge_chunks` already has `UNIQUE (document_id, chunk_index)`, so writes
  upsert on it, and chunks beyond the new count are deleted. Re-running after a
  partial failure converges rather than accumulating.
* **Tenant-scoped.** Every statement carries `organization_id`, including the
  ones that look like they only need a document id. A document id alone is
  never sufficient to read or write anything.
* **Safe to lose a race.** A document deleted mid-ingest must not resurrect as
  chunk rows; the final write re-checks the document still exists, in the same
  transaction.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from uuid import UUID

import psycopg

from opspilot_agent.db import to_vector_literal
from opspilot_agent.knowledge.chunking import chunk_text
from opspilot_agent.knowledge.extraction import ExtractionError, extract_text
from opspilot_agent.knowledge.storage import ObjectNotFoundError, StorageError
from opspilot_agent.providers.errors import AiProviderError
from opspilot_agent.providers.protocols import EmbeddingProvider


class DocumentStatus(StrEnum):
    """Mirrors services/api's `KnowledgeDocumentStatus`; the DB column is the shared contract."""

    UPLOADING = "UPLOADING"
    QUEUED = "QUEUED"
    PROCESSING = "PROCESSING"
    READY = "READY"
    FAILED = "FAILED"


class IndexErrorCode(StrEnum):
    """Failure codes this module itself records, beyond the extractor's own."""

    OBJECT_MISSING = "INDEX_OBJECT_MISSING"
    STORAGE_UNREADABLE = "INDEX_STORAGE_UNREADABLE"
    EMBEDDING_FAILED = "INDEX_EMBEDDING_FAILED"
    NO_CHUNKS = "INDEX_NO_CHUNKS"


@dataclass(frozen=True)
class IndexOutcome:
    document_id: UUID
    status: DocumentStatus
    chunks_written: int = 0
    error_code: str | None = None

    @property
    def succeeded(self) -> bool:
        return self.status is DocumentStatus.READY


@dataclass(frozen=True)
class _DocumentRow:
    id: UUID
    organization_id: UUID
    mime_type: str
    object_key: str
    status: DocumentStatus


class DocumentNotFoundError(Exception):
    """No such document in that organization — deleted, or never visible to this tenant."""


async def index_document(
    connection: psycopg.Connection,
    *,
    organization_id: UUID,
    document_id: UUID,
    object_store,
    embeddings: EmbeddingProvider,
    chunk_tokens: int,
    chunk_overlap_tokens: int,
) -> IndexOutcome:
    """Indexes one document. Never raises for an expected failure — records it and returns."""
    document = _load_document(connection, organization_id, document_id)

    if document.status is DocumentStatus.READY:
        # Already indexed. Re-running is allowed (it converges), but the common case of a
        # redelivered event should not redo the work.
        return IndexOutcome(document_id, DocumentStatus.READY, _count_chunks(connection, document))

    _set_status(connection, document, DocumentStatus.PROCESSING)
    connection.commit()

    try:
        content = object_store.read(document.object_key)
    except ObjectNotFoundError:
        return _fail(connection, document, IndexErrorCode.OBJECT_MISSING)
    except StorageError:
        return _fail(connection, document, IndexErrorCode.STORAGE_UNREADABLE)

    try:
        text = extract_text(content, document.mime_type)
    except ExtractionError as error:
        # A broken PDF lands here: the document goes FAILED with the extractor's own code, which
        # tells a human whether a retry could help (docs/DATA_MODEL.md allows FAILED -> QUEUED).
        return _fail(connection, document, error.code)

    chunks = chunk_text(text, chunk_tokens=chunk_tokens, overlap_tokens=chunk_overlap_tokens)
    if not chunks:
        return _fail(connection, document, IndexErrorCode.NO_CHUNKS)

    try:
        vectors = await embeddings.embed([chunk.text for chunk in chunks])
    except AiProviderError:
        # Provider failures are already bounded by T15's retry budget and circuit breaker; by the
        # time one surfaces here it is terminal for this attempt. The document stays retryable.
        return _fail(connection, document, IndexErrorCode.EMBEDDING_FAILED)

    _write_chunks(connection, document, chunks, vectors, embeddings.model_id)
    _set_status(connection, document, DocumentStatus.READY, error_code=None)
    connection.commit()

    return IndexOutcome(document.id, DocumentStatus.READY, len(chunks))


def _load_document(
    connection: psycopg.Connection, organization_id: UUID, document_id: UUID
) -> _DocumentRow:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT id, organization_id, mime_type, object_key, status
              FROM knowledge_documents
             WHERE id = %s AND organization_id = %s
            """,
            (document_id, organization_id),
        )
        row = cursor.fetchone()

    if row is None:
        raise DocumentNotFoundError(f"No document {document_id} in organization {organization_id}.")
    return _DocumentRow(row[0], row[1], row[2], row[3], DocumentStatus(row[4]))


def _count_chunks(connection: psycopg.Connection, document: _DocumentRow) -> int:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT count(*) FROM knowledge_chunks
             WHERE document_id = %s AND organization_id = %s
            """,
            (document.id, document.organization_id),
        )
        return cursor.fetchone()[0]


def _set_status(
    connection: psycopg.Connection,
    document: _DocumentRow,
    status: DocumentStatus,
    *,
    error_code: str | None = None,
) -> None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE knowledge_documents
               SET status = %s, error_code = %s, updated_at = now()
             WHERE id = %s AND organization_id = %s
            """,
            (status.value, error_code, document.id, document.organization_id),
        )


def _fail(connection: psycopg.Connection, document: _DocumentRow, code: str) -> IndexOutcome:
    connection.rollback()
    _set_status(connection, document, DocumentStatus.FAILED, error_code=str(code))
    connection.commit()
    return IndexOutcome(document.id, DocumentStatus.FAILED, 0, str(code))


def _write_chunks(
    connection: psycopg.Connection,
    document: _DocumentRow,
    chunks,
    vectors: list[list[float]],
    embedding_model: str,
) -> None:
    with connection.cursor() as cursor:
        # A document deleted since this ingest began must not come back as chunk rows. The FK is
        # ON DELETE CASCADE, so without this check the insert would succeed and then be silently
        # cascaded away — or, worse, race the delete. Re-checking inside this transaction makes
        # the outcome deterministic.
        cursor.execute(
            "SELECT 1 FROM knowledge_documents WHERE id = %s AND organization_id = %s FOR UPDATE",
            (document.id, document.organization_id),
        )
        if cursor.fetchone() is None:
            raise DocumentNotFoundError(
                f"Document {document.id} was deleted while it was being indexed."
            )

        for chunk, vector in zip(chunks, vectors, strict=True):
            cursor.execute(
                """
                INSERT INTO knowledge_chunks
                       (organization_id, document_id, chunk_index, chunk_text,
                        embedding, embedding_model, token_count)
                VALUES (%s, %s, %s, %s, %s::vector, %s, %s)
                ON CONFLICT (document_id, chunk_index) DO UPDATE
                   SET chunk_text      = EXCLUDED.chunk_text,
                       embedding       = EXCLUDED.embedding,
                       embedding_model = EXCLUDED.embedding_model,
                       token_count     = EXCLUDED.token_count
                """,
                (
                    document.organization_id,
                    document.id,
                    chunk.index,
                    chunk.text,
                    to_vector_literal(vector),
                    embedding_model,
                    chunk.estimated_tokens,
                ),
            )

        # Re-indexing a document that now yields fewer chunks must not leave the old tail behind.
        cursor.execute(
            """
            DELETE FROM knowledge_chunks
             WHERE document_id = %s AND organization_id = %s AND chunk_index >= %s
            """,
            (document.id, document.organization_id, len(chunks)),
        )
