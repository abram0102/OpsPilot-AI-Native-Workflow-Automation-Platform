-- See docs/DATA_MODEL.md section A/C. Composite UNIQUE/FK pairs
-- (customers.(id,organization_id) <- tickets.(customer_id,organization_id),
-- and the equivalent for tickets <- ticket_messages/ticket_tags) are the
-- "preferably composite foreign keys" defense-in-depth the data model calls
-- for: a ticket literally cannot reference a customer from another
-- organization, enforced by Postgres itself, not just application code
-- (docs/DATA_MODEL.md section B.2's example).
CREATE TABLE customers (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    display_name     TEXT NOT NULL,
    email            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_customers_id_org UNIQUE (id, organization_id)
);

CREATE INDEX idx_customers_org_created ON customers (organization_id, created_at);

CREATE TABLE tickets (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    customer_id      UUID NOT NULL,
    subject          TEXT NOT NULL,
    description      TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    priority    TEXT NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    assignee_id  UUID REFERENCES users (id),
    created_by   UUID NOT NULL REFERENCES users (id),
    -- Optimistic locking (docs/DATA_MODEL.md, docs/API_CONTRACT.md 409 STATE_CONFLICT).
    version      BIGINT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tickets_id_org UNIQUE (id, organization_id),
    CONSTRAINT fk_tickets_customer_same_org FOREIGN KEY (customer_id, organization_id)
        REFERENCES customers (id, organization_id)
);

CREATE INDEX idx_tickets_org_status_created ON tickets (organization_id, status, created_at DESC);
CREATE INDEX idx_tickets_customer ON tickets (customer_id);

CREATE TABLE ticket_messages (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    ticket_id        UUID NOT NULL,
    author_type      TEXT NOT NULL CHECK (author_type IN ('USER', 'AGENT')),
    author_id        UUID REFERENCES users (id),
    body             TEXT NOT NULL,
    -- Set only when an agent-authored message was actually published via an
    -- approved action (docs/DATA_MODEL.md invariant #5). The FK to
    -- tool_executions/agent_runs is added once those tables exist in T17 -
    -- adding a NOT NULL/CHECK tying this to an approval now would be
    -- premature given no approval pipeline exists yet.
    published_by   UUID REFERENCES users (id),
    source_run_id  UUID,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_ticket_messages_ticket_same_org FOREIGN KEY (ticket_id, organization_id)
        REFERENCES tickets (id, organization_id),
    -- A USER message always has a human author; an AGENT message never does
    -- (it's attributed to the run instead, once source_run_id gets its FK).
    CONSTRAINT chk_ticket_messages_author CHECK (
        (author_type = 'USER' AND author_id IS NOT NULL)
        OR (author_type = 'AGENT' AND author_id IS NULL)
    )
);

CREATE INDEX idx_ticket_messages_ticket ON ticket_messages (ticket_id, created_at);

CREATE TABLE ticket_tags (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    ticket_id          UUID NOT NULL,
    tag                TEXT NOT NULL,
    -- FK to agent_runs added once that table exists (T17).
    created_by_run_id  UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_ticket_tags_ticket_same_org FOREIGN KEY (ticket_id, organization_id)
        REFERENCES tickets (id, organization_id),
    CONSTRAINT uq_ticket_tags_ticket_tag UNIQUE (ticket_id, tag)
);
