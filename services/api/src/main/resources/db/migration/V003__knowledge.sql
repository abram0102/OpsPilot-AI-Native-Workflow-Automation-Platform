-- See docs/DATA_MODEL.md section A/D. Flyway owns all schema (AGENTS.md rule 4).
--
-- This migration also creates `outbox_events` — docs/DATA_MODEL.md's migration
-- sequence nominally assigns that table to V005, but T13 (this ticket) needs a
-- real transactional outbox row for "upload complete transitions to QUEUED
-- transactionally" (docs/PRODUCT_AND_ARCHITECTURE.md section 4: "inserts an
-- outbox event in one DB transaction"). Created here, ahead of its originally
-- planned slot, rather than faked — see docs/DECISIONS.md for the ADR. V005
-- adds audit_events (and any further outbox indexes a publisher turns out to
-- need) instead of re-creating this table.
CREATE TABLE knowledge_documents (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    uploaded_by      UUID NOT NULL REFERENCES users (id),
    -- Cosmetic only — never used to derive object_key or a filesystem/S3 path
    -- (docs/SECURITY_AND_TESTING.md: "S3 key not derived from filename").
    filename    TEXT NOT NULL,
    mime_type   TEXT NOT NULL,
    object_key  TEXT NOT NULL,
    sha256      TEXT,
    size_bytes  BIGINT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'UPLOADING'
        CHECK (status IN ('UPLOADING', 'QUEUED', 'PROCESSING', 'READY', 'FAILED')),
    error_code  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_knowledge_documents_id_org UNIQUE (id, organization_id),
    CONSTRAINT uq_knowledge_documents_org_object_key UNIQUE (organization_id, object_key)
);

CREATE INDEX idx_knowledge_documents_org_created ON knowledge_documents (organization_id, created_at DESC);

-- Not populated until T16 (ingestion) — created now because V003 is this
-- project's one knowledge-schema migration slot (docs/DATA_MODEL.md section D),
-- same forward-schema precedent as T06's ticket_tags.created_by_run_id.
CREATE TABLE knowledge_chunks (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    document_id       UUID NOT NULL,
    chunk_index       INT NOT NULL,
    chunk_text        TEXT NOT NULL,
    embedding         vector(1536),
    embedding_model   TEXT,
    token_count       INT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_knowledge_chunks_document_same_org FOREIGN KEY (document_id, organization_id)
        REFERENCES knowledge_documents (id, organization_id) ON DELETE CASCADE,
    CONSTRAINT uq_knowledge_chunks_document_index UNIQUE (document_id, chunk_index)
);

CREATE INDEX idx_knowledge_chunks_org_document ON knowledge_chunks (organization_id, document_id);

CREATE TABLE outbox_events (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type       TEXT NOT NULL,
    aggregate_type   TEXT NOT NULL,
    aggregate_id     UUID NOT NULL,
    organization_id  UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    payload          JSONB NOT NULL,
    status           TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PUBLISHED', 'DEAD')),
    attempts         INT NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ,
    published_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_events_status_next_attempt ON outbox_events (status, next_attempt_at);
