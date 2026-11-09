"""Tenant-scoped vector search over indexed chunks.

docs/DATA_MODEL.md states the invariant this module exists to uphold:
"Retrieval always filters org and doc `READY`, and any supplied document IDs
must be verified against org."

Both filters are in the SQL itself, not applied to the results afterwards.
`organization_id` is a required argument with no default, so there is no way to
call this and accidentally search everything — the failure mode would be silent
cross-tenant disclosure, which docs/SECURITY_AND_TESTING.md treats as its own
class of regression.

Search is exact (`ORDER BY embedding <=> query`), with no approximate index.
docs/DATA_MODEL.md: "start exact and benchmark before adopting approximate
indexes" — at this corpus size an exact scan is both fast and correct, and an
HNSW index would trade recall for a speed-up nothing has yet asked for.
"""

from __future__ import annotations

from dataclasses import dataclass
from uuid import UUID

import psycopg

from opspilot_agent.db import to_vector_literal
from opspilot_agent.providers.protocols import EmbeddingProvider

DEFAULT_LIMIT = 5
MAX_LIMIT = 50


@dataclass(frozen=True)
class RetrievedChunk:
    """docs/API_CONTRACT.md's `RetrievedChunk`: document_id, chunk_index, title, text, score.

    `score` is cosine *similarity* in [0, 1] — higher is closer. pgvector's `<=>` returns cosine
    distance, so it is converted here rather than leaking an operator detail into the graph.
    """

    document_id: UUID
    chunk_index: int
    title: str
    text: str
    score: float


async def search_knowledge(
    connection: psycopg.Connection,
    *,
    organization_id: UUID,
    query: str,
    embeddings: EmbeddingProvider,
    limit: int = DEFAULT_LIMIT,
    document_ids: list[UUID] | None = None,
) -> list[RetrievedChunk]:
    """Nearest chunks to `query` within one organization's READY documents."""
    if not query.strip():
        return []
    limit = max(1, min(limit, MAX_LIMIT))

    [vector] = await embeddings.embed([query])

    # A caller-supplied document filter is applied *inside* the org-scoped query, so passing
    # another tenant's document id narrows the result set to nothing rather than reaching it.
    document_filter = "AND c.document_id = ANY(%(document_ids)s)" if document_ids else ""

    sql = f"""
        SELECT c.document_id,
               c.chunk_index,
               d.filename,
               c.chunk_text,
               1 - (c.embedding <=> %(query_vector)s::vector) AS score
          FROM knowledge_chunks c
          JOIN knowledge_documents d
            ON d.id = c.document_id
           AND d.organization_id = c.organization_id
         WHERE c.organization_id = %(organization_id)s
           AND d.status = 'READY'
           AND c.embedding IS NOT NULL
           {document_filter}
         ORDER BY c.embedding <=> %(query_vector)s::vector
         LIMIT %(limit)s
    """

    params: dict[str, object] = {
        "organization_id": organization_id,
        "query_vector": to_vector_literal(vector),
        "limit": limit,
    }
    if document_ids:
        params["document_ids"] = list(document_ids)

    with connection.cursor() as cursor:
        cursor.execute(sql, params)
        rows = cursor.fetchall()

    return [
        RetrievedChunk(
            document_id=row[0],
            chunk_index=row[1],
            title=row[2],
            text=row[3],
            score=float(row[4]),
        )
        for row in rows
    ]
