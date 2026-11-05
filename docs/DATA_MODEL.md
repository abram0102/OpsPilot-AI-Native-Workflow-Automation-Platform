# Data Model, Transaction Rules and State Machines

All actual schema changes must be made through **Flyway** SQL migrations. No `hibernate.ddl-auto=update` or direct production DDL. Use PostgreSQL 16 with pgvector. The schema below is a design specification (Codex implements exact SQL, FKs and tests); do not assume that reading this file provisions a database.

## A. Core relational tables

| Table | Key columns and meaning | Important constraints |
|---|---|---|
| `users` | `id uuid PK`, `auth_subject text`, `email text`, `display_name`, `created_at`, `updated_at` | UNIQUE `auth_subject`; verified email provenance; email not sole identity key |
| `organizations` | `id`, `name`, `slug`, `created_by uuid`, timestamps | UNIQUE `slug` |
| `memberships` | `id`, `organization_id`, `user_id`, `role OWNER/ADMIN/MEMBER/VIEWER`, timestamps | UNIQUE(org,user); FKs; last OWNER cannot be removed |
| `invitations` | `id`, `organization_id`, `email`, `role`, `token_hash`, `expires_at`, `accepted_at`, `created_by` | One-use hashed secret; cannot invite OWNER without owner-specific logic |
| `customers` | `id`, `organization_id`, `display_name`, `email nullable`, timestamps | Index `(organization_id, created_at)`; avoid real PII in demo |
| `tickets` | `id`, `organization_id`, `customer_id`, `subject`, `description`, `status`, `priority`, `assignee_id nullable`, `created_by`, `version`, timestamps | FKs; default `OPEN`; CHECK enum; optimistic locking using version; indexes `(organization_id,status,created_at desc)` |
| `ticket_messages` | `id`, `organization_id`, `ticket_id`, `author_type USER/AGENT`, `author_id nullable`, `body`, `published_by nullable`, `source_run_id nullable`, `created_at` | org+ticket consistency; agent publication must have approval/action audit |
| `ticket_tags` | `id`, `organization_id`, `ticket_id`, `tag text`, `created_by_run_id nullable` | UNIQUE `(ticket_id,tag)`; all org scoped |
| `knowledge_documents` | `id`, `organization_id`, `uploaded_by`, `filename`, `mime_type`, `object_key`, `sha256`, `size_bytes`, `status`, `error_code`, timestamps | UNIQUE `(organization_id,object_key)`; private storage; size/checksum validated |
| `knowledge_chunks` | `id`, `organization_id`, `document_id`, `chunk_index`, `chunk_text`, `embedding vector(1536)`, `embedding_model`, `token_count`, timestamps | UNIQUE `(document_id,chunk_index)`; index org+doc; no cross-tenant retrieval |
| `agent_runs` | `id`, `organization_id`, `ticket_id`, `initiated_by`, `status`, `checkpoint_thread_id`, `attempt_count`, `lease_owner`, `lease_expires_at`, `steps_used`, `total_input_tokens`, `total_output_tokens`, `last_error_code`, timestamps | FK; index `(organization_id,created_at desc)` and status/lease; queued runs durable |
| `agent_steps` | `id`, `organization_id`, `run_id`, `sequence`, `step_type`, `status`, `sanitized_input jsonb`, `sanitized_output jsonb`, `duration_ms`, `model`, `tokens_in/out`, `error_code`, `created_at` | UNIQUE `(run_id,sequence)` or event id; no raw secrets |
| `approvals` | `id`, `organization_id`, `run_id`, `ticket_id`, `tool_call_id`, `action_type`, `proposal jsonb`, `status`, `decision_by nullable`, `decision_at nullable`, timestamps | UNIQUE `(run_id,tool_call_id)`; only PENDING -> APPROVED/REJECTED/EXPIRED; proposal immutable |
| `tool_executions` | `id`, `organization_id`, `run_id`, `tool_call_id`, `action_type`, `approval_id nullable`, `result_ref nullable`, `status`, timestamps | UNIQUE `(run_id,tool_call_id)`; one effect per logical call; log before/after securely |
| `outbox_events` | `id`, `event_type`, `aggregate_type`, `aggregate_id`, `organization_id`, `payload jsonb`, `status`, `attempts`, `next_attempt_at`, `published_at`, `created_at` | event ID unique; PENDING -> PUBLISHED / DEAD; controlled retry |
| `audit_events` | `id`, `organization_id`, `actor_type`, `actor_id`, `event_type`, `target_type`, `target_id`, `metadata jsonb`, `occurred_at` | append-only service behavior, redact secrets |
| `agent_usage` | `id`, `organization_id`, `run_id`, `model`, `input_tokens`, `output_tokens`, `estimated_cost_usd numeric`, `created_at` | numerical actual tokens vs estimated spend clearly labeled |

### Optional physical schemas

Business tables can live in `public` initially; if separating `app` and `langgraph` schemas, document search paths and Flyway ownership explicitly. LangGraph checkpoint tables are initialized by the installed checkpointer package and use stable `thread_id=agent_runs.id`, **not** manually invented checkpoint columns. Never clear checkpoints when there are pending approvals.

## B. DB invariants

1. Every domain table contains an `organization_id` except global user/organization lookup or internal system tables.
2. Tenant consistency across FKs must be verified in service code and preferably composite foreign keys where practical. Example: a ticket in Org A cannot reference a customer in Org B.
3. All tenant query methods require organization-scoped filters, including vector retrieval. Add integration tests using Org A/Org B fixtures.
4. Avoid accidental exposure via unrestricted `/tickets/{uuid}`. Authorize org membership before fetching or returning resource.
5. Make `ticket_messages` agent publication require `tool_executions` and approval reference when created by automated runs.
6. All event payloads contain stable IDs only, never live access tokens or giant document bodies.
7. `outbox_events` publication is in a separate transaction from business changes, but the original outbox row is inserted *in the same transaction* as the business change.
8. Unique constraints are part of idempotency; do not rely on in-process locks.
9. Prefer UTC `timestamptz` fields; all IDs UUID; PostgreSQL functions may generate UUIDs with extensions where supported.
10. Chunk embeddings must record embedding model/version, otherwise re-embedding changes retrieval correctness.

## C. State machines

### Ticket

`OPEN -> IN_PROGRESS -> RESOLVED -> CLOSED`, with `RESOLVED -> IN_PROGRESS` allowed. Reject unknown statuses and illegal changes with 409. A ticket may be replied to while OPEN or IN_PROGRESS. Enforce optimistic version for conflicting human updates.

### Knowledge document

`UPLOADING -> QUEUED -> PROCESSING -> READY` or `FAILED`; retries `FAILED -> QUEUED`. Deleted metadata must delete underlying private object and chunks under a retention policy.

### Agent run

`QUEUED -> RUNNING -> AWAITING_APPROVAL -> RUNNING -> COMPLETED`

Failures: `QUEUED/RUNNING -> RETRY_SCHEDULED -> QUEUED`, eventually `FAILED`; explicit cancellation from QUEUED/AWAITING_APPROVAL -> `CANCELLED` (running cancellation is cooperative). Rejected approval -> `COMPLETED` with `rejected` outcome or `CANCELLED` with reason, consistently specified in implementation; choose one and document it (recommend `COMPLETED` with `decision=REJECTED` result, because the run handled the rejection successfully).

### Approval

`PENDING -> APPROVED | REJECTED | EXPIRED`; terminal and immutable. Approval decision records actor. Only OWNER/ADMIN may decide. Approval must reference exact immutable proposal including text/body, action type and ticket target. Double approve returns existing outcome and has no duplicate effect (or 409 with stable error; choose and document, recommend idempotent same outcome).

### Tool execution

`PROPOSED -> AUTHORIZED -> EXECUTING -> SUCCEEDED`, and `EXECUTING -> FAILED_RETRYABLE/FAILED_PERMANENT`. Same `(run_id, tool_call_id)` returns persisted outcome on replay. Ensure SQL transaction and unique constraint covers ticket message insertion or tag update to guarantee once-only visible effect.

## D. Migration sequence

- `V001__extensions_and_core.sql`: `vector` extension, users, organizations, memberships, invitations.
- `V002__tickets.sql`: customers, tickets, messages, tags, constraints.
- `V003__knowledge.sql`: document metadata, chunks and B-tree filters; postpone HNSW until measured.
- `V004__agents.sql`: runs, steps, approvals, executions, usage.
- `V005__operations.sql`: outbox, audit events and indexes.
- `V006__hardening.sql`: any composite FKs, additional tenant constraints, supporting indexes, approved migration only.

**Migration tests:** fresh DB migrates to latest; re-running is safe; existing data preserved; rollback documented for destructive migrations; Testcontainers integration tests verify constraints.

## E. Entity modeling notes

- Never store raw full OIDC token, LLM API key, MFA code, or secrets in DB logs.
- AI providers may receive customer ticket text. Use synthetic demo data and a clear data disclosure before real integrations.
- `knowledge_chunks` may contain business-private content. Retrieval always filters org and doc `READY`, and any supplied document IDs must be verified against org.
- Indexes are chosen using `EXPLAIN (ANALYZE, BUFFERS)` on representative synthetic tenants. For pgvector, approximate HNSW post-filter behavior may weaken recall with tenant isolation; start exact and benchmark before adopting approximate indexes.
