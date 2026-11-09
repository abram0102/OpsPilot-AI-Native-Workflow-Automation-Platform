-- Agent run, step, approval, tool-execution and usage tables (docs/DATA_MODEL.md
-- section A; docs/TASKS.md T17). Nothing writes these yet — the start-run API is
-- T18, the graph is T19 and the approval endpoints are T22 — but the schema and
-- its constraints land here so those tickets build on enforced invariants rather
-- than introducing them.
--
-- `outbox_events` is NOT created here: T13 created it in V003 ahead of its
-- originally planned slot (docs/DECISIONS.md ADR-035), which promised this
-- migration would not re-create it.

CREATE TABLE agent_runs (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id      UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    ticket_id            UUID NOT NULL,
    initiated_by         UUID NOT NULL REFERENCES users (id),
    status               TEXT NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'AWAITING_APPROVAL', 'RETRY_SCHEDULED',
                          'COMPLETED', 'FAILED', 'CANCELLED')),
    -- LangGraph addresses a checkpoint by thread id, and docs/DATA_MODEL.md requires that to be
    -- the run id rather than a separately invented scheme. Nullable until a graph actually runs.
    checkpoint_thread_id UUID,
    attempt_count        INT NOT NULL DEFAULT 0,
    -- An expiring lease, so a worker that dies mid-run does not strand it (T25's reclaimer reads
    -- these; docs/API_CONTRACT.md "Reclaim abandoned pending entries after lease expiry").
    lease_owner          TEXT,
    lease_expires_at     TIMESTAMPTZ,
    steps_used           INT NOT NULL DEFAULT 0,
    total_input_tokens   INT NOT NULL DEFAULT 0,
    total_output_tokens  INT NOT NULL DEFAULT 0,
    last_error_code      TEXT,
    -- T18 requires "double-click start with same idempotency key gives one run". The column lives
    -- here, with the table it belongs to, rather than arriving as a later patch migration.
    idempotency_key      TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Lets child rows carry a composite FK, so a child can never point at a run in another
    -- tenant even if its organization_id were wrong.
    CONSTRAINT uq_agent_runs_id_org UNIQUE (id, organization_id),
    CONSTRAINT fk_agent_runs_ticket_same_org FOREIGN KEY (ticket_id, organization_id)
        REFERENCES tickets (id, organization_id) ON DELETE CASCADE
);

-- Scoped to the organization: two tenants may reuse the same key without colliding, and a
-- partial index keeps rows that never supplied one out of it entirely.
CREATE UNIQUE INDEX uq_agent_runs_org_idempotency_key
    ON agent_runs (organization_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- The run list is always "this organization's runs, newest first" (docs/DATA_MODEL.md).
CREATE INDEX idx_agent_runs_org_created_at ON agent_runs (organization_id, created_at DESC);

-- Supports the reclaimer's question: which runs are claimable right now? Only non-terminal
-- states can be, so the partial index stays small as completed runs accumulate.
CREATE INDEX idx_agent_runs_claimable ON agent_runs (status, lease_expires_at)
    WHERE status IN ('QUEUED', 'RUNNING', 'AWAITING_APPROVAL', 'RETRY_SCHEDULED');

CREATE TABLE agent_steps (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    run_id           UUID NOT NULL,
    sequence         INT NOT NULL,
    -- docs/DATA_MODEL.md names the columns but not these enums; they are defined by this ticket
    -- (docs/DECISIONS.md ADR-041) to match the graph T19 will actually walk.
    step_type        TEXT NOT NULL
        CHECK (step_type IN ('CLASSIFY', 'RETRIEVE', 'DRAFT_REPLY', 'PROPOSE_TOOL',
                             'AWAIT_APPROVAL', 'EXECUTE_TOOL', 'FINALIZE')),
    status           TEXT NOT NULL
        CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    -- "sanitized_" is the contract, not decoration: docs/DATA_MODEL.md requires "no raw secrets"
    -- and docs/SECURITY_AND_TESTING.md requires sanitized summaries rather than raw payloads.
    sanitized_input  JSONB,
    sanitized_output JSONB,
    duration_ms      INT,
    model            TEXT,
    tokens_in        INT,
    tokens_out       INT,
    error_code       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_agent_steps_run_same_org FOREIGN KEY (run_id, organization_id)
        REFERENCES agent_runs (id, organization_id) ON DELETE CASCADE,
    -- Replaying a step must overwrite, never append a second copy of the same position.
    CONSTRAINT uq_agent_steps_run_sequence UNIQUE (run_id, sequence)
);

CREATE INDEX idx_agent_steps_run ON agent_steps (run_id, sequence);

CREATE TABLE approvals (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    run_id           UUID NOT NULL,
    ticket_id        UUID NOT NULL,
    -- The agent's own identifier for the call this approval authorises. Together with run_id it
    -- is the idempotency key AGENTS.md names for business actions.
    tool_call_id     TEXT NOT NULL,
    action_type      TEXT NOT NULL
        CHECK (action_type IN ('ADD_INTERNAL_TAG', 'PUBLISH_INTERNAL_REPLY')),
    -- The exact proposal a human is asked to approve. docs/DATA_MODEL.md: "proposal immutable" —
    -- enforced in the service, since what must never happen is a request body substituting a
    -- different payload between proposal and execution.
    proposal         JSONB NOT NULL,
    status           TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    decision_by      UUID REFERENCES users (id),
    decision_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_approvals_id_org UNIQUE (id, organization_id),
    CONSTRAINT fk_approvals_run_same_org FOREIGN KEY (run_id, organization_id)
        REFERENCES agent_runs (id, organization_id) ON DELETE CASCADE,
    CONSTRAINT fk_approvals_ticket_same_org FOREIGN KEY (ticket_id, organization_id)
        REFERENCES tickets (id, organization_id) ON DELETE CASCADE,
    -- One approval per logical tool call, in the database rather than in process memory
    -- (docs/DATA_MODEL.md: "Unique constraints are part of idempotency").
    CONSTRAINT uq_approvals_run_tool_call UNIQUE (run_id, tool_call_id),
    -- A decided approval must carry who decided it and when; a pending one must carry neither.
    CONSTRAINT ck_approvals_decision_consistent CHECK (
        (status = 'PENDING' AND decision_by IS NULL AND decision_at IS NULL)
        OR (status = 'EXPIRED' AND decision_by IS NULL)
        OR (status IN ('APPROVED', 'REJECTED') AND decision_by IS NOT NULL
            AND decision_at IS NOT NULL)
    )
);

-- The approval inbox: this organization's pending decisions, oldest first.
CREATE INDEX idx_approvals_org_status_created
    ON approvals (organization_id, status, created_at);

CREATE TABLE tool_executions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    run_id           UUID NOT NULL,
    tool_call_id     TEXT NOT NULL,
    action_type      TEXT NOT NULL
        CHECK (action_type IN ('ADD_INTERNAL_TAG', 'PUBLISH_INTERNAL_REPLY')),
    approval_id      UUID,
    -- A reference to what the effect produced (a ticket message id, say) rather than a copy of it.
    result_ref       TEXT,
    status           TEXT NOT NULL DEFAULT 'PROPOSED'
        CHECK (status IN ('PROPOSED', 'AUTHORIZED', 'EXECUTING', 'SUCCEEDED',
                          'FAILED_RETRYABLE', 'FAILED_PERMANENT')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_tool_executions_run_same_org FOREIGN KEY (run_id, organization_id)
        REFERENCES agent_runs (id, organization_id) ON DELETE CASCADE,
    -- Cascades like every other child of a run. An execution only ever loses its approval when
    -- the whole run or organization goes, at which point this row would go too; the durable
    -- record of what happened lives in append-only `audit_events`, not here.
    CONSTRAINT fk_tool_executions_approval_same_org FOREIGN KEY (approval_id, organization_id)
        REFERENCES approvals (id, organization_id) ON DELETE CASCADE,
    -- "one effect per logical call" (docs/DATA_MODEL.md) — the constraint that makes a replayed
    -- delivery return the persisted outcome instead of acting twice.
    CONSTRAINT uq_tool_executions_run_tool_call UNIQUE (run_id, tool_call_id)
);

CREATE INDEX idx_tool_executions_run ON tool_executions (run_id);

CREATE TABLE agent_usage (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    run_id             UUID NOT NULL,
    model              TEXT NOT NULL,
    -- Actual token counts reported by the provider. Kept separate from estimated_cost_usd so a
    -- measured number is never confused with a derived one (docs/DATA_MODEL.md: "numerical actual
    -- tokens vs estimated spend clearly labeled").
    input_tokens       INT NOT NULL DEFAULT 0,
    output_tokens      INT NOT NULL DEFAULT 0,
    estimated_cost_usd NUMERIC(12, 6),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_agent_usage_run_same_org FOREIGN KEY (run_id, organization_id)
        REFERENCES agent_runs (id, organization_id) ON DELETE CASCADE
);

CREATE INDEX idx_agent_usage_org_created_at ON agent_usage (organization_id, created_at DESC);
