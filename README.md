# OpsPilot

**AI-native customer support platform.** A multi-tenant SaaS where a support team manages tickets, uploads company knowledge, and runs an AI agent that triages tickets, retrieves tenant-scoped knowledge (RAG), drafts a cited reply — and can never publish anything without a human approving the exact proposed text first.

[![CI](https://github.com/abram0102/OpsPilot-AI-Native-Workflow-Automation-Platform/actions/workflows/ci.yml/badge.svg)](https://github.com/abram0102/OpsPilot-AI-Native-Workflow-Automation-Platform/actions/workflows/ci.yml)

## What it does

- 🏢 **Multi-tenant organizations** — invite-based membership, four-tier roles (Owner/Admin/Member/Viewer), every row scoped and enforced server-side, never trusted from the client.
- 🎫 **Ticket workspace** — searchable, filterable, keyset-paginated ticket queues with an internal conversation thread and optimistic-concurrency-safe status transitions.
- 📚 **Knowledge base & RAG** — upload TXT/MD/PDF docs; they're chunked, embedded (Postgres + pgvector) and retrieved strictly within the asking tenant's own data.
- 🤖 **An AI agent on a leash** — a LangGraph-orchestrated worker classifies tickets, retrieves cited knowledge, and proposes a reply, but publishing it always requires an Owner/Admin to approve the *exact* proposal it generated.
- 🔁 **Durable async execution** — agent runs and tool effects survive a worker crash or a Redis outage: transactional outbox, at-least-once delivery, idempotent effects, checkpointed resumption.
- 🔍 **Full audit trail** — every run, decision and tool execution is traced and queryable, not collapsed into a final status.

## Architecture

```
Browser ⇄ Next.js (BFF, Auth0 session)
            │  server-side bearer token forward
            ▼
      Spring Boot API  ──  PostgreSQL + pgvector
            │  transactional outbox → Redis Streams
            ▼
  Python / LangGraph agent worker  ──  LLM + embedding providers
```

- The browser never holds an access token — the Next.js BFF holds the session and forwards a bearer token server-side.
- Tenant isolation is enforced twice: application-level role checks *and* composite foreign keys in Postgres, so a bug in one layer can't leak another organization's data.
- The agent worker never performs a business action directly — every ticket write goes through a private, service-authenticated internal API that re-validates policy, approval state and idempotency from scratch. Its own indexing and retrieval read and write the knowledge tables under an explicit tenant filter.

Full spec: [`docs/PRODUCT_AND_ARCHITECTURE.md`](docs/PRODUCT_AND_ARCHITECTURE.md).

## Tech stack

| Layer | Stack |
|---|---|
| Frontend | Next.js 16 (App Router, Cache Components), React 19, TypeScript, Auth0 (OIDC) |
| Backend API | Spring Boot 4 / Java 21, Spring Security (OAuth2 resource server), Spring Data JPA, Flyway |
| AI worker | Python 3.12, LangGraph, FastAPI, Pydantic v2 |
| Data | PostgreSQL 16 + pgvector, Redis Streams |
| Infra / CI | Docker Compose (local dev), GitHub Actions, Testcontainers |

## Engineering highlights

- **Security-first authorization** — every organization-scoped request resolves the caller's role from the database, never from a client-supplied value; cross-tenant access is its own tested class of security regression.
- **Human-in-the-loop AI** — the agent can draft and propose, never publish; every AI-authored action requires an explicit approval against the immutable proposal it actually produced.
- **Test discipline** — real Postgres via Testcontainers in CI, covering tenant isolation, invalid state transitions, stale-write conflicts and replay attacks for every shipped feature.
- **Spec-driven build** — the full product/architecture/security spec in [`docs/`](docs) was written before any implementation code, and the system is built one reviewed increment at a time.

## Getting started

```bash
cp .env.example .env   # fill in local-only placeholder values; never commit .env
make help               # list available targets
make infra-up           # start local Postgres+pgvector and Redis (Docker required)
make api-dev             # services/api on :8080
make agent-dev           # services/agent on :8000
make web-dev              # apps/web on :3000
```

## Repository layout

```
apps/web/               # Next.js 16 / React 19 / TypeScript frontend
services/api/           # Spring Boot / Java 21 backend (business API + policy)
services/agent/         # Python 3.12 / LangGraph AI worker
packages/contracts/     # generated OpenAPI spec + typed TS client
infra/                  # docker-compose.yml, infra/aws/ (Terraform/CDK)
docs/                   # product, architecture, security and task specs
evals/                  # AI evaluation datasets and reports
tests/e2e/               # Playwright end-to-end tests
benchmarks/             # load-test scripts and recorded results
```

## Project status

Built one reviewed ticket at a time from a 36-ticket backlog. Organizations, membership/invitations, ticket CRUD and management screens, a typed API client generated from the real backend (with a CI check that fails on drift), and knowledge document upload (local + S3 storage, with its management UI) are live and CI-verified today; the AI agent, approvals, RAG pipeline and cloud deployment are still ahead. The authoritative, per-ticket evidence log lives in [`docs/PROGRESS.md`](docs/PROGRESS.md).

## Documentation

| File | Contents |
|---|---|
| `docs/PRODUCT_AND_ARCHITECTURE.md` | PRD, user flows, architecture, module boundaries |
| `docs/DATA_MODEL.md` | PostgreSQL schema, constraints, state machines |
| `docs/API_CONTRACT.md` | HTTP/event/agent-tool contracts |
| `docs/SECURITY_AND_TESTING.md` | Threat model, mandatory test cases, CI gates |
| `docs/THREAT_MODEL.md` | Each threat, its mitigation, and the test that holds it (checked by CI) |
| `docs/OBSERVABILITY.md` | Metrics, alert thresholds and what is deliberately not measured |
| `docs/FAILURE_DRILLS.md` | Failure drills with observed outcomes, and the backup/restore procedure |
| `benchmarks/2026-11-13-report.md` | Measured before/after load test and query plans, with the limits stated |
| `docs/TASKS.md` | Sequential implementation backlog |
| `docs/DECISIONS.md` | Architecture decision record |
| `docs/PROGRESS.md` | Per-ticket status and evidence (source of truth) |

## License

No license has been selected yet (see `docs/DECISIONS.md`, ADR-006). Until one is added, all rights are reserved by the owner.
