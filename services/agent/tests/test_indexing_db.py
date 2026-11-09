"""Indexing and retrieval against a real PostgreSQL + pgvector.

Marked `db`: skipped where there is no database (Gate H0), run in CI against a
`pgvector/pgvector:pg16` service container. These cover the acceptance criteria
that only a real database can demonstrate — chunks actually persisted, the right
document cited, one tenant never reaching another's data, repeated ingest not
duplicating, and a broken PDF landing in FAILED with a recoverable code.
"""

from __future__ import annotations

from pathlib import Path
from uuid import uuid4

import pytest

from opspilot_agent.knowledge.extraction import ExtractionErrorCode
from opspilot_agent.knowledge.indexer import (
    DocumentNotFoundError,
    DocumentStatus,
    IndexErrorCode,
    index_document,
)
from opspilot_agent.knowledge.retrieval import search_knowledge
from opspilot_agent.knowledge.storage import LocalObjectStore
from opspilot_agent.providers.mock import MockEmbeddingProvider
from tests.conftest import insert_document, insert_user

pytestmark = pytest.mark.db

FAQ_CORPUS = Path(__file__).resolve().parents[3] / "evals" / "datasets" / "faq_corpus_v1"


@pytest.fixture
def embeddings() -> MockEmbeddingProvider:
    return MockEmbeddingProvider(dimensions=1536)


@pytest.fixture
def object_store(tmp_path: Path) -> LocalObjectStore:
    return LocalObjectStore(tmp_path)


def store_object(store: LocalObjectStore, organization_id, content: bytes) -> str:
    """Writes an object the way the Java local adapter would, and returns its key."""
    key = f"orgs/{organization_id}/knowledge/{uuid4()}"
    path = store.base_dir / key
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return key


async def index(connection, organization_id, document_id, object_store, embeddings):
    return await index_document(
        connection,
        organization_id=organization_id,
        document_id=document_id,
        object_store=object_store,
        embeddings=embeddings,
        chunk_tokens=120,
        chunk_overlap_tokens=20,
    )


def seed_document(connection, store, organization_id, *, filename, content, mime="text/markdown"):
    user_id = insert_user(connection, f"{uuid4()}@example.test")
    key = store_object(store, organization_id, content)
    document_id = insert_document(
        connection,
        organization_id=organization_id,
        uploaded_by=user_id,
        filename=filename,
        object_key=key,
        mime_type=mime,
    )
    return document_id


class TestIndexing:
    async def test_indexes_the_three_synthetic_faq_documents(
        self, db_connection, organization, object_store, embeddings
    ) -> None:
        """The acceptance case: three synthetic docs, extracted and persisted."""
        document_ids = []
        for path in sorted(FAQ_CORPUS.glob("*.md")):
            if path.name == "README.md":
                continue
            document_ids.append(
                seed_document(
                    db_connection,
                    object_store,
                    organization,
                    filename=path.name,
                    content=path.read_bytes(),
                )
            )
        assert len(document_ids) == 3

        for document_id in document_ids:
            outcome = await index(
                db_connection, organization, document_id, object_store, embeddings
            )
            assert outcome.status is DocumentStatus.READY
            assert outcome.chunks_written > 0

        with db_connection.cursor() as cursor:
            cursor.execute(
                "SELECT count(*) FROM knowledge_chunks WHERE organization_id = %s",
                (organization,),
            )
            assert cursor.fetchone()[0] > 3

    async def test_records_the_embedding_model_on_every_chunk(
        self, db_connection, organization, object_store, embeddings
    ) -> None:
        # docs/DATA_MODEL.md: re-embedding changes retrieval correctness, so the producing model
        # must be stored alongside the vector.
        document_id = seed_document(
            db_connection,
            object_store,
            organization,
            filename="f.md",
            content=b"Refunds take 5 days.",
        )

        await index(db_connection, organization, document_id, object_store, embeddings)

        with db_connection.cursor() as cursor:
            cursor.execute(
                "SELECT DISTINCT embedding_model FROM knowledge_chunks WHERE document_id = %s",
                (document_id,),
            )
            assert [row[0] for row in cursor.fetchall()] == [embeddings.model_id]

    async def test_moves_the_document_to_ready(
        self, db_connection, organization, object_store, embeddings
    ) -> None:
        document_id = seed_document(
            db_connection, object_store, organization, filename="f.md", content=b"Shipping is fast."
        )

        await index(db_connection, organization, document_id, object_store, embeddings)

        assert _status_of(db_connection, document_id) == ("READY", None)

    async def test_repeated_ingest_does_not_duplicate_chunks(
        self, db_connection, organization, object_store, embeddings
    ) -> None:
        """At-least-once delivery means this will happen in production."""
        content = (FAQ_CORPUS / "shipping_policy.md").read_bytes()
        document_id = seed_document(
            db_connection, object_store, organization, filename="s.md", content=content
        )

        first = await index(db_connection, organization, document_id, object_store, embeddings)
        # Force a genuine re-index rather than the early READY return.
        _force_status(db_connection, document_id, "QUEUED")
        second = await index(db_connection, organization, document_id, object_store, embeddings)

        assert first.chunks_written == second.chunks_written
        assert _chunk_count(db_connection, document_id) == first.chunks_written

    async def test_reindexing_shorter_content_removes_the_stale_tail(
        self, db_connection, organization, object_store, embeddings
    ) -> None:
        long_content = (FAQ_CORPUS / "billing_policy.md").read_bytes()
        document_id = seed_document(
            db_connection, object_store, organization, filename="b.md", content=long_content
        )
        first = await index(db_connection, organization, document_id, object_store, embeddings)
        assert first.chunks_written > 1

        # Replace the stored object with something much shorter and re-index.
        key = _object_key_of(db_connection, document_id)
        (object_store.base_dir / key).write_bytes(b"Short now.")
        _force_status(db_connection, document_id, "QUEUED")
        second = await index(db_connection, organization, document_id, object_store, embeddings)

        assert second.chunks_written == 1
        assert _chunk_count(db_connection, document_id) == 1

    async def test_an_already_ready_document_is_not_reindexed(
        self, db_connection, organization, object_store, embeddings
    ) -> None:
        document_id = seed_document(
            db_connection, object_store, organization, filename="f.md", content=b"Ready already."
        )
        await index(db_connection, organization, document_id, object_store, embeddings)

        # Deleting the stored object would break a real re-index; the early return means it is
        # never read, which is what makes redelivery cheap.
        key = _object_key_of(db_connection, document_id)
        (object_store.base_dir / key).unlink()
        outcome = await index(db_connection, organization, document_id, object_store, embeddings)

        assert outcome.status is DocumentStatus.READY


class TestFailures:
    async def test_broken_pdf_fails_with_a_recoverable_code(
        self, db_connection, organization, object_store, embeddings
    ) -> None:
        """The acceptance case: broken PDF -> FAILED and a recoverable error code."""
        document_id = seed_document(
            db_connection,
            object_store,
            organization,
            filename="broken.pdf",
            content=b"%PDF-1.7\nnot actually a pdf",
            mime="application/pdf",
        )

        outcome = await index(db_connection, organization, document_id, object_store, embeddings)

        assert outcome.status is DocumentStatus.FAILED
        assert outcome.error_code == ExtractionErrorCode.MALFORMED_DOCUMENT
        status, error_code = _status_of(db_connection, document_id)
        assert status == "FAILED"
        # The code is recorded on the row, so a human can see why without reading logs, and
        # docs/DATA_MODEL.md's FAILED -> QUEUED retry stays available.
        assert error_code == ExtractionErrorCode.MALFORMED_DOCUMENT
        assert _chunk_count(db_connection, document_id) == 0

    async def test_a_missing_stored_object_fails_without_losing_the_document(
        self, db_connection, organization, object_store, embeddings
    ) -> None:
        user_id = insert_user(db_connection)
        document_id = insert_document(
            db_connection,
            organization_id=organization,
            uploaded_by=user_id,
            filename="gone.md",
            object_key=f"orgs/{organization}/knowledge/{uuid4()}",
        )

        outcome = await index(db_connection, organization, document_id, object_store, embeddings)

        assert outcome.error_code == IndexErrorCode.OBJECT_MISSING
        assert _status_of(db_connection, document_id)[0] == "FAILED"

    async def test_an_empty_document_fails_rather_than_becoming_unsearchable_ready(
        self, db_connection, organization, object_store, embeddings
    ) -> None:
        document_id = seed_document(
            db_connection, object_store, organization, filename="empty.md", content=b"   \n  "
        )

        outcome = await index(db_connection, organization, document_id, object_store, embeddings)

        assert outcome.status is DocumentStatus.FAILED
        assert outcome.error_code == ExtractionErrorCode.EMPTY_DOCUMENT

    async def test_a_document_from_another_organization_is_not_indexable(
        self, db_connection, organization, other_organization, object_store, embeddings
    ) -> None:
        document_id = seed_document(
            db_connection, object_store, organization, filename="f.md", content=b"Tenant A data."
        )

        # Presenting a real document id under the wrong tenant must find nothing at all.
        with pytest.raises(DocumentNotFoundError):
            await index(db_connection, other_organization, document_id, object_store, embeddings)

    async def test_a_document_deleted_mid_ingest_leaves_no_chunks(
        self, db_connection, organization, object_store, embeddings
    ) -> None:
        content = (FAQ_CORPUS / "account_policy.md").read_bytes()
        document_id = seed_document(
            db_connection, object_store, organization, filename="a.md", content=content
        )

        class DeletingStore(LocalObjectStore):
            """Deletes the document row at the moment its bytes are read."""

            def read(self, object_key: str) -> bytes:
                data = super().read(object_key)
                with db_connection.cursor() as cursor:
                    cursor.execute("DELETE FROM knowledge_documents WHERE id = %s", (document_id,))
                db_connection.commit()
                return data

        racing_store = DeletingStore(object_store.base_dir)

        with pytest.raises(DocumentNotFoundError):
            await index(db_connection, organization, document_id, racing_store, embeddings)

        assert _chunk_count(db_connection, document_id) == 0


class TestRetrieval:
    @pytest.fixture
    async def indexed_corpus(self, db_connection, organization, object_store, embeddings):
        ids = {}
        for path in sorted(FAQ_CORPUS.glob("*.md")):
            if path.name == "README.md":
                continue
            document_id = seed_document(
                db_connection,
                object_store,
                organization,
                filename=path.name,
                content=path.read_bytes(),
            )
            await index(db_connection, organization, document_id, object_store, embeddings)
            ids[path.stem] = document_id
        return ids

    async def test_returns_chunks_with_a_citable_document_and_title(
        self, db_connection, organization, embeddings, indexed_corpus
    ) -> None:
        results = await search_knowledge(
            db_connection,
            organization_id=organization,
            query="How long does delivery take?",
            embeddings=embeddings,
        )

        assert results
        assert all(r.document_id in indexed_corpus.values() for r in results)
        assert all(r.title.endswith(".md") for r in results)
        assert all(r.text for r in results)

    async def test_never_returns_another_organizations_chunks(
        self, db_connection, organization, other_organization, object_store, embeddings
    ) -> None:
        """Cross-tenant retrieval is its own regression class (docs/SECURITY_AND_TESTING.md)."""
        secret = b"Globex internal: the launch code is on page two."
        mine = seed_document(
            db_connection, object_store, organization, filename="mine.md", content=b"My own notes."
        )
        theirs = seed_document(
            db_connection,
            object_store,
            other_organization,
            filename="theirs.md",
            content=secret,
        )
        await index(db_connection, organization, mine, object_store, embeddings)
        await index(db_connection, other_organization, theirs, object_store, embeddings)

        results = await search_knowledge(
            db_connection,
            organization_id=organization,
            query="launch code",
            embeddings=embeddings,
        )

        assert all(r.document_id != theirs for r in results)
        assert all("launch code" not in r.text for r in results)

    async def test_a_supplied_document_id_from_another_tenant_returns_nothing(
        self, db_connection, organization, other_organization, object_store, embeddings
    ) -> None:
        theirs = seed_document(
            db_connection,
            object_store,
            other_organization,
            filename="theirs.md",
            content=b"Globex confidential pricing.",
        )
        await index(db_connection, other_organization, theirs, object_store, embeddings)

        results = await search_knowledge(
            db_connection,
            organization_id=organization,
            query="pricing",
            embeddings=embeddings,
            document_ids=[theirs],
        )

        assert results == []

    async def test_only_ready_documents_are_searchable(
        self, db_connection, organization, object_store, embeddings
    ) -> None:
        document_id = seed_document(
            db_connection,
            object_store,
            organization,
            filename="pending.md",
            content=b"Refund window is thirty days.",
        )
        await index(db_connection, organization, document_id, object_store, embeddings)
        # A document pulled back for re-processing must drop out of results immediately.
        _force_status(db_connection, document_id, "PROCESSING")

        results = await search_knowledge(
            db_connection,
            organization_id=organization,
            query="refund window",
            embeddings=embeddings,
        )

        assert results == []

    async def test_respects_the_result_limit(
        self, db_connection, organization, embeddings, indexed_corpus
    ) -> None:
        results = await search_knowledge(
            db_connection,
            organization_id=organization,
            query="delivery",
            embeddings=embeddings,
            limit=2,
        )

        assert len(results) <= 2

    async def test_scores_are_similarities_in_range_and_ordered_best_first(
        self, db_connection, organization, embeddings, indexed_corpus
    ) -> None:
        results = await search_knowledge(
            db_connection,
            organization_id=organization,
            query="tracking number",
            embeddings=embeddings,
            limit=5,
        )

        scores = [r.score for r in results]
        assert all(-1.0 <= s <= 1.0 for s in scores)
        assert scores == sorted(scores, reverse=True)

    async def test_an_empty_query_returns_nothing_without_querying(
        self, db_connection, organization, embeddings
    ) -> None:
        assert (
            await search_knowledge(
                db_connection, organization_id=organization, query="   ", embeddings=embeddings
            )
            == []
        )

    async def test_an_organization_with_no_documents_gets_no_results(
        self, db_connection, other_organization, embeddings, indexed_corpus
    ) -> None:
        results = await search_knowledge(
            db_connection,
            organization_id=other_organization,
            query="delivery",
            embeddings=embeddings,
        )

        assert results == []


def _status_of(connection, document_id) -> tuple[str, str | None]:
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT status, error_code FROM knowledge_documents WHERE id = %s", (document_id,)
        )
        return cursor.fetchone()


def _force_status(connection, document_id, status: str) -> None:
    with connection.cursor() as cursor:
        cursor.execute(
            "UPDATE knowledge_documents SET status = %s WHERE id = %s", (status, document_id)
        )
    connection.commit()


def _chunk_count(connection, document_id) -> int:
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT count(*) FROM knowledge_chunks WHERE document_id = %s", (document_id,)
        )
        return cursor.fetchone()[0]


def _object_key_of(connection, document_id) -> str:
    with connection.cursor() as cursor:
        cursor.execute("SELECT object_key FROM knowledge_documents WHERE id = %s", (document_id,))
        return cursor.fetchone()[0]
