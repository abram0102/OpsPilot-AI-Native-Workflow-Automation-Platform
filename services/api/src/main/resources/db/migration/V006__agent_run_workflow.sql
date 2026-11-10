-- Records which workflow a run is executing (docs/TASKS.md T18).
--
-- V004 created `agent_runs` only days ago and this column arguably belonged
-- there, but a migration already on `main` is immutable: editing it changes its
-- checksum, and T17's own acceptance ("V004/V005 support reboots without
-- dropping state") only holds while migrations are append-only. So this is a new
-- file rather than an edit.
--
-- The column is needed, not decorative. docs/API_CONTRACT.md defines the start
-- request body as `{"workflow": "SUPPORT_TRIAGE_V1"}` and requires
-- `409 IDEMPOTENCY_CONFLICT` when a key is "reused with different request body" —
-- which cannot be detected without storing what the first request asked for.
-- T19's graph will also branch on it.

ALTER TABLE agent_runs
    ADD COLUMN workflow TEXT NOT NULL DEFAULT 'SUPPORT_TRIAGE_V1'
        CHECK (workflow IN ('SUPPORT_TRIAGE_V1'));

-- The default exists so the column can be NOT NULL on a table that may already
-- hold rows. Application code always supplies it explicitly; the default is a
-- migration device, not an invitation to omit the value.
