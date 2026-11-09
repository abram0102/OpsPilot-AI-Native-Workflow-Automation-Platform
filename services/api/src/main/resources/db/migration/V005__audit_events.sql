-- Append-only audit trail (docs/DATA_MODEL.md section A; docs/TASKS.md T17).
--
-- `outbox_events` was originally planned for this migration but was created in
-- V003 when T13 needed it (docs/DECISIONS.md ADR-035, which promised V005 would
-- add `audit_events` rather than re-create it). No further outbox index is added
-- here: the publisher that would reveal which indexes it actually needs is T24,
-- and guessing now would mean carrying an unused index until then.

CREATE TABLE audit_events (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    -- Who acted. An agent run is a first-class actor here, so an AI-authored change is
    -- distinguishable from a human one forever after, not merely at the time.
    actor_type       TEXT NOT NULL CHECK (actor_type IN ('USER', 'AGENT', 'SYSTEM')),
    -- The user id, the run id, or NULL for SYSTEM. Deliberately not a foreign key: an audit row
    -- must outlive the thing it describes, and a FK would cascade it away.
    actor_id         UUID,
    event_type       TEXT NOT NULL,
    target_type      TEXT NOT NULL,
    target_id        UUID,
    -- Sanitized context only. docs/SECURITY_AND_TESTING.md: "Log sanitized summaries"; AGENTS.md:
    -- "A trace never logs access tokens, API keys, raw cookies or unnecessary sensitive personal
    -- data." Enforced by the writing service, which is why nothing here is free-form text.
    metadata         JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_audit_events_actor_identified CHECK (
        (actor_type = 'SYSTEM') OR (actor_id IS NOT NULL)
    )
);

-- "What happened in this organization, most recent first" — the only access pattern an audit
-- log has until someone needs more.
CREATE INDEX idx_audit_events_org_occurred_at ON audit_events (organization_id, occurred_at DESC);

CREATE INDEX idx_audit_events_target ON audit_events (organization_id, target_type, target_id);
