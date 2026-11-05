# Sequential Codex Implementation Backlog — T00 to T36

## How to execute these tickets

Implement only ONE ticket per Codex conversation/turn. **Read `AGENTS.md`, `docs/PRODUCT_AND_ARCHITECTURE.md`, `docs/DATA_MODEL.md`, `docs/API_CONTRACT.md`, and `docs/SECURITY_AND_TESTING.md` before work.** For every ticket: make smallest complete change, add tests, run checks, record actual results in `docs/PROGRESS.md`, stop and request review before the next. All services must work without paid API calls in `AI_MODE=mock` where applicable.

An initial ticket may need to create future-required directories and empty documentation templates, but **do not create empty unimplemented API handlers**. Each ticket has explicit inputs, implementation, and acceptance checks. If the current task changes another ticket's assumptions, update the ADR, dependency file and tests, explaining the change.

### PHASE 0 — Engineering foundation

#### T00 — Repository bootstrap, decisions, contribution rules
**Dependencies:** none. **Human gate:** H0 (Git repository owner creates repo).

**Build:** Initialize project layout specified in `AGENTS.md` with directories and workspace root docs. Create `.gitignore`, `.editorconfig`, `.gitattributes`, `LICENSE` choice placeholder only if rights are clear, `docs/DECISIONS.md`, `docs/PROGRESS.md`, `docs/DEPENDENCY_DECISIONS.md`, `Makefile`, a minimum root `README.md` with instructions and status indicators that do not falsely claim implementation. Configure scripts to fail with clear messages for not-yet-created services rather than silently passing. Document line endings, naming conventions, version pin policy, secret hygiene and conventional commits.

**Acceptance:** `git status` shows no accidental generated binary or credential; Markdown references resolve; `make help` prints target descriptions; `docs/PROGRESS.md` marks T00 only; no production feature claims.

#### T01 — Local infrastructure with Docker Compose
**Dependencies:** T00. **Human gate:** Docker must be installed.

**Build:** Compose PostgreSQL 16 image with pgvector available, Redis 7.x, named volumes, health checks, non-default local-only password from `.env`, and default bridge network. Avoid exposing DB/Redis outside localhost. Provide `make infra-up`, `make infra-down`, `make infra-logs`, `make infra-reset` with reset requiring explicit interactive confirmation and clear warning. Create bootstrap SQL only for extensions if required; Flyway owns all application schema. Add `scripts/check-infra.sh` that polls health and tests `SELECT current_database(), extversion...` when extension becomes enabled.

**Acceptance:** `docker compose config` validates; `make infra-up` starts healthy services; PostgreSQL connection succeeds; Redis `PING` succeeds; local volumes survive normal restart; reset is not accidental.

#### T02 — Spring Boot API skeleton
**Dependencies:** T01.

**Build:** Spring Boot compatible stable release with Java 21, Maven Wrapper, modular packages, spring-web, validation, security, OAuth resource server, JPA/JDBC, Flyway, PostgreSQL, Actuator, test tooling/Testcontainers. Add structured `application-local.yml` with env-backed DB config; health/readiness endpoints only. Add consistent error envelope, request ID filter, global exception mapping. Do not expose operational endpoints beyond needs.

**Acceptance:** `./mvnw test` passes, API runs on 8080, liveness/readiness health responds, invalid JSON receives consistent 400 error, no debug auth bypass.

#### T03 — Python AI worker skeleton
**Dependencies:** T01.

**Build:** `uv` or pip lock via managed Python project, Python 3.12, pydantic-settings, FastAPI (health only), LangGraph, psycopg, redis, pytest, ruff; packages with `src/` layout. Implement config loader that validates required env values, mock provider protocol and `/health/live`/`health/ready` without actual agent logic. Dockerfile with non-root user and pinned dependencies.

**Acceptance:** Python lock file committed, `ruff check .` + `pytest` pass, local worker health returns, `AI_MODE=mock` requires no API keys, no implementation of future tasks.

#### T04 — Next.js app skeleton and layout
**Dependencies:** T00.

**Build:** Next.js 16 App Router, React 19, TS strict, modern accessible component primitives; install currently supported stable-compatible versions. Create responsive shell, minimal landing and login placeholder with clear 'not configured' state, error boundary, loading UI and `/app/[orgId]/dashboard` placeholder without fake data. Setup ESLint, formatter, unit test harness, `pnpm-lock.yaml` and non-hardcoded API base URL. Avoid storing tokens in localStorage.

**Acceptance:** `pnpm install --frozen-lockfile`, `pnpm lint`, `pnpm typecheck`, `pnpm test` and `pnpm build` pass; browser shows responsive shell; keyboard focus visible.

#### T05 — Baseline CI and repo quality automation
**Dependencies:** T02–T04.

**Build:** GitHub Actions PR workflow: Java build/tests, Python lint/tests, Next lint/typecheck/tests/build, file/secret detection, caching; use explicit permissions read-only except where necessary. `Makefile` routes targets to service tools. Add renovate/Dependabot compatible configs where available. Do not require AWS or LLM secrets for CI.

**Acceptance:** workflows YAML syntax valid; all jobs reproducibly pass locally; any failure causes workflow failure; no fake passing placeholders or suppressed errors.

### PHASE 1 — Data model, identity, tenant isolation

#### T06 — Core schema and Flyway migrations
**Dependencies:** T02.

**Build:** Implement `V001` and `V002` as in DATA_MODEL; extension, users, orgs, memberships, invitations, customers, tickets, messages, tags; UUID, CHECKs, NOT NULL, indexes, foreign key consistency and UTC timestamps. JPA/JDBC entities and repositories with tenant predicates; migration integration tests with Testcontainers; an explicit seed of synthetic data only for local/test profile.

**Acceptance:** fresh DB migrates with `Flyway validate`; startup restart is idempotent; unique org/user membership enforcement; cross-org customer on ticket rejected in service and DB where practical; sample fixtures are fictional.

#### T07 — Authorization policy service and signed-token test infrastructure
**Dependencies:** T06.

**Build:** Reusable `AuthenticatedPrincipal`, `OrganizationAccessService`, deterministic role policy checks, Spring Security issuer/audience verification. Write tests that mint properly signed test JWT with a local test-only issuer/JWK or Spring JWT test mocks that cannot run in production. No auth skip query parameter/header. Produce role action permission matrix.

**Acceptance:** no bearer/wrong issuer/wrong audience/expired token tests fail auth; owner/member/viewer role tests prove server checks; API never trusts client role claims; production profile refuses test-only issuer.

#### T08 — Auth0 + Next.js BFF OIDC flow
**Dependencies:** T04, T07. **Human gate:** H1 (Auth0 config) for live smoke test.

**Build:** Integrate up-to-date Next.js Auth0 SDK; session stored in secure HttpOnly cookie; access token retrieved on Next.js server then forwarded to Spring API. Implement login/logout, `/me`, authenticated layout and organization selector. Provision internal user by stable OIDC `sub` on first trusted login. Configure callback URL by actual SDK docs, CSRF protection and env validations. Backend is never authenticated only because frontend thinks user is logged in.

**Acceptance:** real test Auth0 sign-in/out and refresh works after H1; missing config yields useful setup error not bypass; test-only signed identity works in automated environment; unauthorized API returns 401.

#### T09 — Organization onboarding, membership, invitation
**Dependencies:** T07–T08.

**Build:** Create org transaction with owner membership, list orgs, members; invite flow with hash-only invitation token, TTL, one-time acceptance; role updates with last-owner protections; server-side role checks and UI forms. Do not send real invitation email for V1; show token via protected preview or copy action in dev only and build secure production invitation delivery as opt-in later (for public staging, avoid exposing tokens in responses to unintended viewers).

**Acceptance:** create->owner, second user accept, role management, token expiration, replay prevention, last owner preservation, cross-tenant invite misuse tests, E2E browser test using test OIDC users.

### PHASE 2 — Customer support application

#### T10 — Ticket CRUD API and paginated lists
**Dependencies:** T06–T09.

**Build:** DTO validation, `POST/GET/PATCH` ticket endpoints, customer creation, internal messages, allowed status transitions, optimistic locking using `version`, cursor pagination stable by `(created_at,id)` and filter by priority/status. Structured 400/403/404/409 errors; add OpenAPI annotations and Testcontainers repository/controller integration tests.

**Acceptance:** org A never accesses org B ticket IDs; invalid transitions 409; stale version 409; 25/100 record pages do not duplicate/skip, role restrictions enforced.

#### T11 — Generate OpenAPI and typed TypeScript client
**Dependencies:** T10.

**Build:** Generate OpenAPI from actual Spring endpoints; commit spec and client with reproducible generator command. Wire Next.js BFF calls through typed client, standard API error response conversion, stable pagination. CI drift check fails when generated spec/client out of sync.

**Acceptance:** `make test-contract` verifies drift; TS compilations match API DTOs; create ticket via typed client works against local API and returns server-provided values.

#### T12 — Ticket frontend screens
**Dependencies:** T11.

**Build:** Dashboard with counts from real APIs (not placeholders), ticket table/filter/pagination/search, create-ticket dialog, ticket details and message thread, status/priority editing with collision errors. Build accessible components and loading/empty/error states; polling only where justified.

**Acceptance:** Playwright create/list/detail/edit/filter, 403 UI error, mobile responsive, user can complete ticket workflow; no mock data presented as live.

#### T13 — Knowledge metadata, local upload + S3 abstraction
**Dependencies:** T10.

**Build:** `V003` knowledge migrations; `StorageService` interface with local-dev filesystem adapter (safe path) and S3 adapter (private bucket, signed URL when configured). Upload-session endpoint validates MIME, extension, size, owner and checksum; completion verifies object existence and metadata, creates outbox ingestion job. For this ticket, ingestion remains QUEUED intentionally; T16 processes it.

**Acceptance:** upload TXT/MD, reject 11 MiB file or unsupported executable, reject forged cross-tenant doc, never path-traverse, file persisted; upload complete transitions to QUEUED transactionally.

#### T14 — Knowledge frontend
**Dependencies:** T11, T13.

**Build:** knowledge list/upload/drop zone, progress, status badges, error messages; refresh and doc delete (owner/admin). Preview file metadata only; never inline untrusted HTML. Show `QUEUED` until ingestion implemented.

**Acceptance:** Playwright upload valid file, invalid type/size rejected, status refresh, cross-tenant request denied; no public file URL.

### PHASE 3 — Agent core and RAG

#### T15 — AI providers with deterministic mock and explicit budgets
**Dependencies:** T03.

**Build:** implement `LLMProvider`, `EmbeddingProvider` protocols and Pydantic structured outputs; deterministic mock seeded by synthetic cases and real provider adapter driven by env with timeouts/retry budget. Embed dimensions always checked (=1536 for default schema), circuit-break expensive calls, input/output token cap, clear error codes. Prompt files version-controlled.

**Acceptance:** offline tests generate reproducible typed classification/reply/embedding; model timeout/malformed JSON raises controlled error; `AI_MODE=mock` makes no network calls; secret never logged.

#### T16 — Knowledge indexer and tenant-scoped retrieval
**Dependencies:** T13, T15.

**Build:** safe text extraction for TXT/MD/PDF, chunk by bounded token size+overlap, embeddings, `knowledge_chunks` with model/version, exact pgvector cosine search with `organization_id` and ready-document filter. Implement idempotent document indexing and status updates; cancellation/cleanup if document deleted. Add worker ingestion handler (initial local direct CLI/test event consumption if Redis dispatcher not yet implemented), and example synthetic FAQ dataset. Avoid HNSW at small scale.

**Acceptance:** upload 3 synthetic docs, extract and persist chunks; queries retrieve correct document citation; org A search never retrieves org B; repeated ingest no duplicates; broken PDF -> FAILED and recoverable error code.

#### T17 — Agent run and approval persistence schema
**Dependencies:** T06, T16.

**Build:** migrations `V004/V005` for `agent_runs`, `agent_steps`, `approvals`, `tool_executions`, `outbox_events`, `audit_events`, `agent_usage`; explicit state enums and constraint indexes, stable JSON structures, list/read API skeleton **only where a working DB implementation exists**. Add DB lease/index support.

**Acceptance:** Flyway/Tcontainers schema tests; approval and execution uniqueness; invalid transitions rejected by service; `V004/V005` support reboots without dropping state.

#### T18 — Start run API and local direct execution adapter
**Dependencies:** T10, T17.

**Build:** `POST tickets/{id}/runs` returns 202, atomically creates `QUEUED` run and outbox event; `GET runs`, `GET run`, `GET steps`, `cancel` with tenant scopes. Add a local test-only direct executor harness that invokes worker on the queued run, but do not claim Redis delivery until T25. Idempotency key enforces one run for repeated same request. Trace and status persisted.

**Acceptance:** client sees runId immediately; DB has outbox event in same transaction; run status visible; double-click start with same idempotency key gives one run; Org B access denied.

#### T19 — LangGraph deterministic AI triage graph
**Dependencies:** T15–T18.

**Build:** typed graph state, persistent Postgres checkpointer, nodes `load_ticket`, `classify`, `retrieve_knowledge`, `draft_reply`, `propose_actions`, `policy_gate`, `finalize`. Use narrow prompts and tools, no autonomous shell/SQL. Bound steps/time/tokens, store structured steps and citations, return terminal FAILED if limits exceeded. Implement direct synchronous test harness **outside HTTP request thread**.

**Acceptance:** mock triage runs on seeded ticket, retrieves tenant doc, creates a correctly typed reply proposal, trace persisted; invalid model proposal blocked; synthetic prompt injection cannot create unauthorized tools; graph can resume from persisted checkpoint.

#### T20 — Real LLM and eval smoke (opt-in)
**Dependencies:** T19. **Human gate:** H2 for external provider calls.

**Build:** real chat+embedding provider, env configurable model IDs, per-run budget counters, backoff/timeout, provider exception mapping. Add `make eval-real` requiring explicit environment flag and max-case budget; offline eval remains default. Document provider data handling for synthetic demo only.

**Acceptance:** with H2, real provider on 3 fixed synthetic tickets returns typed outputs with valid citations; without H2 offline tests all pass and real eval refuses with meaningful setup instructions; actual model/date/cost recorded, not made up.

### PHASE 4 — Actions, approvals and resilience

#### T21 — Deterministic action policy and internal service identity
**Dependencies:** T07, T17, T19.

**Build:** action registry `ADD_INTERNAL_TAG`, `PUBLISH_INTERNAL_REPLY`; role/action/risk policy independent from model; machine-only JWT for private internal API (distinct service audience, no public user bearer accepted). `toolCallId` unique and validated; API reloads target/run/tenant from DB. Implement typed request/response and policy tests.

**Acceptance:** any unknown action denied; browser bearer cannot call internal endpoint; org mismatch denied; fabricated approval denied; tool execution logged and sanitized; no arbitrary command/SQL tool.

#### T22 — Approval API + immutable proposal UI
**Dependencies:** T12, T17, T21.

**Build:** approval record persists immutable proposed reply; list/detail/approve/reject with row lock, actor audit, exact proposal payload and no request-body substitution. Frontend inbox with preview, controls, final decision display, accessibility and conflict state; decision inserts outbox resume event.

**Acceptance:** member/viewer cannot decide; owner/admin can; repeated approve no double state effect; approval event persisted; rejection recorded and cannot later approve; cross-org approver blocked.

#### T23 — Durable LangGraph human-in-the-loop pause/resume
**Dependencies:** T19, T21–T22.

**Build:** checkpoint before external/visible side effect. When publishing is proposed, persist approval and `AWAITING_APPROVAL` state and yield; on approved resume verify same run and proposal, invoke internal action exactly once; on rejected resume finalize with rejection outcome. Ensure checkpoints and DB approval record consistency; document crash windows and reconciliation if dual writes cannot be atomic. Use LangGraph interrupt/resume features, with stable thread ID, not process-local memory.

**Acceptance:** pause persists through worker process stop/start; approve resumes and posts exactly one ticket message; reject posts zero; malformed resume rejected; no automatic publishing bypass.

#### T24 — Outbox publisher and Redis Streams dispatcher
**Dependencies:** T01, T17, T18, T23.

**Build:** Spring background publisher claims pending outbox rows with `FOR UPDATE SKIP LOCKED`, posts to `opspilot:events`, marks published, retries on failure. Distinguish publish-then-crash duplication risk; outbox stays durable when Redis unavailable. Add Redis consumer group config and event schema/version tests.

**Acceptance:** live Redis stream receives event; Redis down leaves pending outbox then eventually publishes on recovery; same event may be published twice but idempotent consumer handles it; missing/unknown schema version DLQ.

#### T25 — Worker consumer, leases, reclaim and DLQ
**Dependencies:** T24.

**Build:** Python `XREADGROUP` consumer and ACK, startup consumer group creation, per-run DB lease, heartbeat and lease expiry, idle message reclaim (`XAUTOCLAIM` compatible), bounded exponential backoff and max attempts, dead-letter stream/terminal FAILED with sanitized reason, shutdown signals. Handle `knowledge.ingest.requested`, `agent.run.requested`, and `agent.run.resume`. Never ACK successful job before durable status/checkpoint persist.

**Acceptance:** tests for double-delivery, worker death mid-run, reclaimer, poison messages and Redis outage; queue health metrics; replay of completed run results in zero duplicate side effect.

#### T26 — Exactly-once business effects under at-least-once delivery
**Dependencies:** T21–T25.

**Build:** atomic transaction for `tool_executions` unique `(runId,toolCallId)` and ticket message/tag mutation; test rollback of failed mutation, idempotent retry result, concurrency races from multiple consumers, audit event consistency. Apply DB uniqueness and row-level locks, not in-memory mutexes; harden cancel/retry races.

**Acceptance:** send 10 concurrent duplicate requests with same call ID -> one message/tag effect; crash after business DB commit and before Redis ACK -> one effect after reclaim; approved action performed only once and audit consistent.

### PHASE 5 — UI and quality gates

#### T27 — Agent Run Explorer and approval integration
**Dependencies:** T12, T18, T22–T26.

**Build:** show run status, step traces, citations, approval waiting state, human decision, retry/failure and usage metrics in UI. Poll with AbortController and interval cleanup, show localized error/empty/loading states. Dashboard aggregates counts from API. Ensure only sanitized data appears.

**Acceptance:** end-to-end UI demo create ticket -> run -> see queued/running -> see approval -> approve -> see published internal reply; errors and duplicate clicks handled without misleading success.

#### T28 — Full security tests and threat-model regression
**Dependencies:** T27.

**Build:** implement mandatory cases from SECURITY_AND_TESTING; especially IDOR, wrong JWT audience, wrong role, approval forgery, malicious knowledge text, provider tool injection, unsupported upload, logs and no secrets. Add static dependency audit and scan alerts. Update threat model table with test links.

**Acceptance:** automated exploit attempts all blocked; two fake tenants isolated on documents, vectors and traces; tests catch deliberate unsafe code path in review; no publicly reachable test auth shortcut.

#### T29 — Comprehensive CI and Playwright E2E
**Dependencies:** T27–T28.

**Build:** signed offline test identities, compose-based isolated test services, browser test infrastructure, integration and contract tests, deterministic fake provider. Add CI matrix/caching, artifact uploads on failure (sanitized) and nonflaky readiness checks. Automate smoke in PR CI, optional nightly more expensive tests.

**Acceptance:** `make check` passes on a clean checkout with Docker; relevant UI flows run via Playwright; red tests reliably block merges; test report artifacts reproducible.

#### T30 — Evaluation harness, metrics and observability
**Dependencies:** T20, T26–T29 (can do mock evaluation without T20).

**Build:** at least 60 synthetic eval entries, confusion matrix classification, wrong-tool and unauthorized-action checks, grounded citations, report JSON+Markdown, prompt version/model/cost stamping, OpenTelemetry request/run trace IDs, API p95 metrics, worker queue lag, failure/approval metrics, Sentry error integration as optional env. Add dashboard/monitoring guide.

**Acceptance:** `make eval-mock` produces reproducible report; all high-risk unauthorized-action tests have zero executed violations; actual latency and token statistics measured or honestly unknown; traces redact credentials.

### PHASE 6 — Cloud and operations

#### T31 — AWS infrastructure as code (staging first)
**Dependencies:** T29–T30. **Human gate:** H3 for any AWS apply.

**Build:** infrastructure templates for private VPC/subnets, RDS PostgreSQL 16 with pgvector compatibility, Redis-compatible service, private S3 with encryption/block public access, ECS/Fargate service tasks for Spring API and Python worker, ECR, ALB/API TLS, IAM roles, CloudWatch, secrets. Include cost-sensitive sizing variables, delete protection, logging, environment boundaries. Use Terraform or AWS CDK, choose one and document. Provide `plan` command safe to run only after credentials; never auto apply.

**Acceptance:** IaC lint/validate passes, reviewable plan available after H3, DB and Redis private, no wildcard admin role, HTTPS public app path, secrets in secrets manager, no hardcoded credentials, pgvector migration smoke on staging.

#### T32 — Build images and GitHub Actions deployment
**Dependencies:** T31. **Human gate:** H3 GitHub OIDC trust / env approvals.

**Build:** multi-stage Dockerfiles for web/API/worker with pinned versions and non-root users; GitHub Actions PR builds; protected environment deployment via OIDC role, image SHA tagging, sequential migration job and smoke checks; rollback to previous task definition/image. Control Next.js runtime env and route API securely.

**Acceptance:** local image builds and health checks; GitHub OIDC trust validated in staging; no long-lived AWS credentials; deployment logs and rollback command documented; rollout failures do not silently mark success.

#### T33 — Staging deploy, monitoring, backup/restore and failure drill
**Dependencies:** T32. **Human gate:** H3/H4.

**Build:** execute only after explicit permission, configure DNS/HTTPS, CloudWatch metrics/alarms, backup/restore instructions and retention, private S3, alert verification, production-like synthetic data, migration strategy. Drill killing worker after action DB commit, Redis outage, failed LLM and RDS unavailable; document outcomes, not guesses.

**Acceptance:** user can access staging URL via HTTPS; auth and tenant E2E pass; approval and one-action-only replay holds; dashboard alert fires on test failure; DB backup/restoration documented and tested; actual cloud costs monitored.

### PHASE 7 — Engineering evidence and showcase

#### T34 — Performance benchmarking and query optimization
**Dependencies:** T33 (can benchmark local first).

**Build:** k6 scripts for tickets list/create and run status, 10k-ticket fixture set, baseline P50/P95/P99/error throughput, DB `EXPLAIN ANALYZE`, 1–2 measured improvements (pagination/index/serialization). Separate model latency from API latency. Save raw JSON and readable report with environment/hardware/time/model data; do not claim fabricated numbers.

**Acceptance:** one-command reproducible load test, before/after benchmark artefacts, query-plan evidence, documented limits and remaining bottlenecks.

#### T35 — Portfolio documentation, diagrams and demo
**Dependencies:** T34.

**Build:** README with screenshots from real running app, live demo URL if deployed, architecture diagram, API docs link, synthetic dataset privacy note, test/eval metrics with evidence links, tradeoffs and failure modes. Record 3–5 minute demo scenario and script: two tenant accounts -> knowledge -> ticket -> AI run -> approval -> published reply -> traces -> recovery story. Write 2–4 résumé bullets using measured metrics only.

**Acceptance:** third-party developer can bring up mock stack using documented commands and run demo; screenshots show working features; links are real; resume bullet claims supported by benchmarks.

#### T36 — Final audit and release candidate
**Dependencies:** all above.

**Build:** run full test matrix, review open issues/security backlog, confirm no accidentally exposed credentials, inspect cloud billing, UX/a11y smoke, source docs version, quality gates, smoke test from clean checkout. Produce a final release report describing pass/fail of each V1 success criterion, known limitations, a 30-day operations plan and future roadmap. Tag release only on human approval.

**Acceptance:** all release-blocking invariants pass; non-blocking issues documented with severity; final build and deploy logs stored; never state 'production ready' if security, E2E or backup gate is incomplete.

---

## Acceptance summary by phase

| Phase | Exit demonstration |
|---|---|
| 0 T00–T05 | Three services compile/test; local DB+Redis healthy; PR CI green |
| 1 T06–T09 | Real OIDC login and 2-tenant role-scoped org management |
| 2 T10–T14 | Real tickets + local/S3 knowledge upload UI/API |
| 3 T15–T20 | Deterministic and opt-in real AI triage + cited retrieval + persistent run |
| 4 T21–T26 | Approvals, checkpoint resume, outbox/stream, crash replay with single effect |
| 5 T27–T30 | E2E frontend, security regression, evaluation and observability |
| 6 T31–T33 | Authorized AWS staging, short-lived CI credentials, recovery and alerts |
| 7 T34–T36 | Measured benchmarks, polished evidence-backed demo and release report |

## Important sequencing adjustments allowed

- T08 Auth0 can be temporarily deferred while using **signed test-only identity** for local API tests; public app cannot be launched with a bypass. Record dependency and never mark T08 done until live login works.
- T20 real-LLM smoke requires user-funded API key; all mock work can continue first.
- T31–T33 require cloud credentials and billing permission. You can finish robust local code and demo beforehand; do not pretend production deploy occurred.
- Auth0 hosted and AWS hosted services may change APIs. Verify current official docs and pin versions; don't silently replace entire architecture just to avoid fixing an integration.
- If time constrained, ship MVP with safe PostgreSQL-backed jobs before Redis Streams, but mark T24–T26 incomplete and do **not** claim Redis or delivery guarantees on the résumé.
