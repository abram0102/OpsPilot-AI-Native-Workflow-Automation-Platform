# Product Requirements and Architecture — OpsPilot V1

## 0. Purpose and boundaries

**Product:** Multi-tenant SaaS for customer support operations. A tenant's team manages customer tickets, uploads verified knowledge, invokes an AI support agent to classify and summarize a ticket, retrieve company-specific knowledge, propose actions, request permission before publishing a reply, and view immutable execution/audit records.

**Recruiter-facing differentiators:** full-stack product, security by design, strong Java backend, typed APIs, Postgres and pgvector, Python agent orchestration, durable async execution, human approval, CI/CD, observability and measured performance/evaluation results.

**Not** an autonomous CRM, refund engine or generic agent framework. Real external emailing is a later optional integration, only after explicit human authorization. V1 'publish reply' posts to an internal ticket conversation, not an external recipient.

## 1. Users and permissions

- `OWNER`: create organization, manage membership/settings, approve/reject, manage knowledge, view audit, full ticket operations.
- `ADMIN`: invite and manage users except owner transfer, approve/reject, manage knowledge and tickets.
- `MEMBER`: view/modify authorized organization tickets, run agent, upload permitted documents; **cannot** approve high-risk actions by default.
- `VIEWER`: read organization tickets/runs and dashboard only, no agent runs or mutation.
- Each user can belong to multiple organizations; organization selector persists in server-side session or route, but backend re-checks membership on each request.
- `auth_subject` from validated OIDC identity resolves to internal `users.id`, memberships and roles. Client cannot assert role.

## 2. User stories (acceptance-level)

- U01: New authenticated user creates an organization and becomes owner, redirects to dashboard.
- U02: Owner invites an existing user by email (V1 can use a verified invitation token; no production emails required) and adjusts their role. Authorization prevents self-granting owner rights.
- U03: Member creates/edits/paginates/filters support tickets; all tickets are scoped to the current tenant.
- U04: Member uploads a supported text/PDF knowledge document with explicit limit. Indexer extracts/chunks/embeds and displays per-document indexing status.
- U05: Member starts an agent run for a selected ticket. API quickly returns `202 Accepted` with run ID; UI shows state updates without blocking HTTP.
- U06: Agent classifies and summarizes the ticket, finds tenant-scoped knowledge, records source citations in response draft, and proposes a route or draft.
- U07: Agent can automatically add a safe internal tag if policy allows; publishing its drafted response requires ADMIN/OWNER approval.
- U08: Approver views **exact proposed content**, approving/rejecting via API, and sees the result in ticket thread. Duplicate requests are idempotent.
- U09: Users can inspect run trace (timestamp, step name, sanitized inputs/outputs, token usage, errors) and audit events.
- U10: Worker/Redis outages do not lose committed runs, pending approvals, or approved actions; recover safely when restarted.
- U11: User can compare AI quality and service performance via documented reproducible tests.

## 3. Scope and release gates

### MVP-A: Works without any paid third-party service

- `docker compose up` PostgreSQL + pgvector and Redis; frontend/backend/worker run locally.
- Deterministic mock AI for tests + local seeded tenants and synthetic tickets.
- CRUD tickets, document ingest (TXT / Markdown), mock classification + retrieval via deterministic embeddings.
- All essential tests run without external credentials.
- Authentication tests use signed mock JWT fixtures from local test-only issuer; no public insecure auth bypass.

### MVP-B: Real AI with human approval

- Auth0 OIDC properly configured, real embedding and chat provider configured via environment, PDF ingest added.
- Agent runs async; traced tool calls; approval; retry/replay; security tests.
- Staging deployment and complete live demo.

### V1 portfolio-quality

- Multi-tenant security, seeded second tenant; performance metrics, test reports, RAG evaluation, incident drill, screenshots, a 3–5 minute demo, concise engineering architecture writeup.

### Later (not blocking V1)

- Gmail/Slack/OAuth integrations, Webhook adapters, real email publishing, drag/drop visual workflow designer, Kafka, Kubernetes, advanced billing, custom roles, zero-downtime global HA.

## 4. Data and request flows

### Synchronous browser interaction

1. Browser signs in through Auth0 using standard OIDC SDK. Browser gets HttpOnly session cookie for Next.js BFF; no long-lived bearer in localStorage.
2. Next.js server-side request handler retrieves server-held access token, forwards request to Spring Boot API.
3. Spring Security validates JWT signature, issuer, audience, expiry; app resolves user, then target organization membership and role.
4. Spring Controller -> Service -> tenant-scoped Repository -> PostgreSQL; API returns DTO with no internal entities or secrets.

### Ingest and RAG

1. Frontend requests a time-limited tenant-scoped upload URL / signed upload flow, then completes upload.
2. API validates MIME, size, checksum, storage key and organization; changes knowledge document state to `QUEUED` and inserts an outbox event in one DB transaction.
3. Outbox dispatcher publishes an event to Redis Stream; indexer consumes and validates document metadata; safely parses TXT/MD/PDF (no macros, network fetches or arbitrary scripts).
4. Indexer chunks text (start: ~600 tokens with ~100 overlap), calls configured embedding provider (or deterministic mock), writes `knowledge_chunks` rows with tenant scope, embedding model/version and citation reference; document marked `READY` or `FAILED`.
5. Query retrieval executes SQL **with tenant filter before or during vector selection**, returns chunk text and attribution. Start with exact similarity + B-tree organization filter. Add HNSW only if benchmarks justify it and monitor tenant-filter recall.

### Agent execution

1. `POST /api/v1/organizations/{orgId}/tickets/{ticketId}/runs` makes one `agent_runs` row `QUEUED` and one outbox event within the same database transaction.
2. Dispatcher publishes `agent.run.requested` to `opspilot:events` Redis Stream (consumer group `agent-workers`).
3. Python worker claims a message and attempts to acquire an expiring DB lease; re-checks run and tenant context. A duplicate event does nothing if already processed.
4. LangGraph checkpoint loads/initializes per-run state. Nodes: `load_ticket -> classify -> retrieve_knowledge -> propose_actions -> policy_gate -> (safe_action or await_approval) -> finalize`. Agent has <= `AGENT_MAX_STEPS` and token/time budgets. No open-ended recursive loops.
5. Approval required? persist proposal and checkpoint; run state `AWAITING_APPROVAL`. Worker ACKs the **paused** run message only after durable checkpoint and approval persistence are confirmed. API approval inserts a new outbox `agent.run.resume` event.
6. Resume worker reads checkpoint with same run/thread ID, atomically checks approval, executes allowlisted action through backend internal API with idempotency key, saves trace, marks completed or failed, then ACKs.
7. Reclaimer handles expired leases / stale pending stream entries and re-enqueues safely. Events are **at least once**; exactly-once effects are enforced by DB uniqueness and action state checks, not by assuming transport delivers once.

### Internal service action API

Python worker cannot directly bypass business policy. Its business actions call a Spring Boot internal endpoint with a service identity **not accepted from public clients**, and a run ID + tool call ID. API loads run, ticket and organization from DB, not from the tool's claimed scope; enforces allowlisted action, permission, approval, idempotency and audit trail. Production endpoints are network-private and protected by machine credentials, not just network isolation. For local development use test-signed service identity (never production fallback).

## 5. Concrete modules

### Spring Boot API modules

- `auth`: OIDC JWT validation, user provisioning, org membership and role checking.
- `organization`: organization/membership operations; invitations.
- `ticket`: customer records, ticket CRUD, tags, internal conversation, pagination.
- `knowledge`: upload session, document lifecycle and metadata.
- `run`: request start, status, trace view, cancel, approvals.
- `policy`: authorization and allowed agent actions, independent of LLM.
- `internal`: private service action execution endpoint, idempotency and audit.
- `dispatch`: transactional outbox and Redis Stream publisher, background reconciliation.
- `observability`: correlation IDs, request metrics and health/readiness endpoints.

### Python AI worker modules

- `worker`: Redis consumer group, lease management, checkpoint recovery.
- `providers`: mock and real LLM/embedding adapters; typed deterministic interfaces.
- `retrieval`: chunking, embeddings and tenant-safe vector search.
- `graphs`: LangGraph orchestration and graph state/checkpointer.
- `tools`: strict Pydantic schemas for read-only and authorized write proposals.
- `evals`: golden-set datasets and reproducible evaluation CLI.
- `telemetry`: sanitized spans, token/cost capture, error classes.

### Next.js routes

- `/` marketing/demo intro
- `/sign-in` Auth0 login
- `/onboarding` org creation
- `/app/[orgId]/dashboard` high-level ticket/run stats
- `/app/[orgId]/tickets` searchable table, filters, pagination
- `/app/[orgId]/tickets/[ticketId]` detail, conversation, start run, trace
- `/app/[orgId]/knowledge` upload/status/list and indexed excerpts
- `/app/[orgId]/runs` run explorer with states, duration, steps and errors
- `/app/[orgId]/approvals` pending/decided actions and approve/reject controls
- `/app/[orgId]/settings/members` roles and invite flow
- `/app/[orgId]/settings/audit` audit events (owner/admin only)

## 6. UX behavior / design specification

- Clean, responsive B2B SaaS visual language: left nav, narrow top toolbar, clear badges for status, accessible light/dark surfaces.
- Every table has loading, empty, error and paginated states, keyboard focus and accessible labels.
- Ticket detail has three panes or tabs: conversation, AI proposal, execution history.
- Approvals show exact before/after impact; do not replace audit data with generic 'approved' toast.
- Worker long-running state is shown by polling every 2–5 seconds initially, with abort on unmount; SSE optional later if tested.
- Never display successful status when job is merely queued or when approval hasn't executed.
- No realistic customer PII in screenshots or public demo: use clearly labeled synthetic examples.

## 7. Repository layout

```
apps/web/src/app/...
apps/web/src/components/...
apps/web/src/lib/api/...
services/api/src/main/java/com/opspilot/{auth,organization,ticket,knowledge,run,policy,internal,dispatch,common}
services/api/src/main/resources/db/migration/V001__...sql
services/api/src/test/java/...
services/agent/src/opspilot_agent/{worker,providers,retrieval,graphs,tools,db,telemetry}
services/agent/tests/...
packages/contracts/openapi.yaml          # generated from backend
packages/contracts/typescript/...         # generated client
tests/e2e/...
infra/docker-compose.yml
infra/aws/...
evals/datasets/...
benchmarks/...
scripts/...
```

## 8. Technical decisions and tradeoffs

- **PostgreSQL is system of record**; Redis Streams is delivery transport, not durable source of truth. Use outbox + idempotency to close transaction/queue publish gap.
- **No Kafka** for v1. Redis Streams fits modest throughput; record when Kafka could become justified.
- **No Kubernetes** for v1. AWS Fargate supports independently scaled API and worker with lower operations overhead.
- **Real production authentication via established OIDC**, not homegrown password storage.
- **LLM is not a policy engine**. Use deterministic policy guards, typed tool registries, human approval and evaluated workflows.
- **No HNSW until measured**; pgvector exact searches + tenant B-tree index are often a better starting point for small isolated datasets.
- **No speculative microservice splitting**: a modular monolith backend + separate AI worker is a deliberate architecture choice.
- **No claims of HIPAA or SOC2 compliance**: this app uses fictional support records, not health data; controls are portfolio-grade engineering practices, not a compliance certification.

## 9. Definition of Done for release

A release is complete only if: two-tenant isolation tests pass; API returns typed responses and authorization errors; agent mock and real-mode smoke tests pass; all risky publishing needs approval; double submission has one effect; restart recovery tests pass; production deployment is documented; observability captures trace IDs and failed work; evaluation and load tests produce archived actual outputs; README/demo can be followed by a third party.
