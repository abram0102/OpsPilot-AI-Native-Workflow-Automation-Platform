"""The ingestion handler, and a CLI that drives it directly.

T13's `completeUpload` already writes an outbox row, but nothing publishes it
yet: the Redis dispatcher is T24 and the consumer that will call this handler is
T25. So this ticket provides the handler plus a direct entry point, exactly as
docs/TASKS.md T16 allows ("initial local direct CLI/test event consumption if
Redis dispatcher not yet implemented").

`handle_ingest_event` takes the event payload shape the outbox row already
carries — `{"documentId", "organizationId"}` — so T25 wires a consumer to this
function without reshaping anything.

The name mismatch noted here during T16 is settled: T24 renamed the outbox
row's `event_type` to `knowledge.ingest.requested`, matching the envelope in
docs/API_CONTRACT.md. This handler never read the name — it reads the payload —
so nothing here changed.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from uuid import UUID

from opspilot_agent.config import Settings, get_settings
from opspilot_agent.db import connect
from opspilot_agent.knowledge.indexer import IndexOutcome, index_document
from opspilot_agent.knowledge.storage import LocalObjectStore
from opspilot_agent.providers.factory import build_embedding_provider


async def handle_ingest_event(payload: dict, settings: Settings | None = None) -> IndexOutcome:
    """Indexes the document an ingest event names.

    At-least-once delivery means this can be called twice for the same document; indexing is
    idempotent, so a redelivery converges rather than duplicating chunks.
    """
    settings = settings or get_settings()
    try:
        organization_id = UUID(str(payload["organizationId"]))
        document_id = UUID(str(payload["documentId"]))
    except (KeyError, ValueError) as error:
        raise ValueError(
            "Ingest event payload needs `documentId` and `organizationId` UUIDs."
        ) from error

    embeddings = build_embedding_provider(settings)
    object_store = LocalObjectStore(settings.storage_local_dir)

    with connect(settings) as connection:
        return await index_document(
            connection,
            organization_id=organization_id,
            document_id=document_id,
            object_store=object_store,
            embeddings=embeddings,
            chunk_tokens=settings.chunk_tokens,
            chunk_overlap_tokens=settings.chunk_overlap_tokens,
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m opspilot_agent.knowledge.handler",
        description="Index one knowledge document (stand-in for the T25 Redis consumer).",
    )
    parser.add_argument("--organization-id", required=True)
    parser.add_argument("--document-id", required=True)
    args = parser.parse_args(argv)

    outcome = asyncio.run(
        handle_ingest_event(
            {"organizationId": args.organization_id, "documentId": args.document_id}
        )
    )

    print(
        json.dumps(
            {
                "documentId": str(outcome.document_id),
                "status": outcome.status.value,
                "chunksWritten": outcome.chunks_written,
                "errorCode": outcome.error_code,
            }
        )
    )
    return 0 if outcome.succeeded else 1


if __name__ == "__main__":
    sys.exit(main())
