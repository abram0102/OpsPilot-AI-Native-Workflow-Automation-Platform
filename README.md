# OpsPilot

**AI-native customer support operations platform — planning baseline only. No application code exists yet.**

OpsPilot is a multi-tenant SaaS where a support team manages tickets, uploads company knowledge, and runs an AI agent that classifies tickets, retrieves tenant-scoped knowledge (RAG), proposes a reply with citations, and requires human approval before anything is published. See `docs/PRODUCT_AND_ARCHITECTURE.md` for the full product and architecture spec.

## Status

| Component | Status |
|---|---|
| Planning docs (this repo's `docs/`) | ✅ Committed |
| `apps/web` (Next.js) | ✅ Skeleton done — lint/typecheck/test/build all pass, verified in a real browser (T04) |
| `services/api` (Spring Boot) | 🟡 Skeleton written, 10/10 Docker-independent tests pass — full run needs a live Postgres (T02, Gate H0) |
| `services/agent` (Python / LangGraph) | ✅ Skeleton done — 10/10 tests pass, health verified over real HTTP (T03) |
| Local infra (`infra/docker-compose.yml`) | 🟡 Written, unverified — Docker not installed in dev environment (T01, Gate H0) |
| CI | ⬜ Not started (T05) |
| Auth0 login | ⬜ Not started (T08) |
| Agent runs, approvals, RAG | ⬜ Not started (T15–T26) |
| Staging / AWS deployment | ⬜ Not started (T31–T33) |

This table is updated honestly as each ticket in `docs/TASKS.md` actually passes its acceptance checks — never ahead of verified work. See `docs/PROGRESS.md` for the authoritative per-ticket log.

## What this repository is

This is a specification-driven build: every planning document in `docs/` was written before any implementation, and `AGENTS.md` sets mandatory engineering rules (tenant isolation, human approval before risky AI actions, no fabricated test results, no committed secrets) that every future ticket must follow. Implementation proceeds one ticket at a time from `docs/TASKS.md` (T00 through T36); each ticket stops for human review before the next begins.

## Getting started (once services exist)

```bash
cp .env.example .env   # fill in local-only placeholder values; never commit .env
make help               # list available targets
make infra-up           # start local Postgres+pgvector and Redis (Docker required)
```

Until the corresponding ticket lands, `make` targets for services that don't exist yet will fail with a clear message pointing at the ticket that implements them — they will not silently no-op or pretend to succeed.

## Repository layout

```
AGENTS.md              # mandatory engineering contract for every ticket
README_START_HERE.md   # how this blueprint is meant to be executed
CONTRIBUTING.md         # naming, versioning, secrets, commit conventions
apps/web/               # Next.js 16 / React 19 / TypeScript frontend
services/api/           # Spring Boot / Java 21 backend (business API + policy)
services/agent/         # Python 3.12 / LangGraph AI worker
packages/contracts/     # generated OpenAPI spec + typed TS client
infra/                  # docker-compose.yml, infra/aws/ (Terraform/CDK, human-gated)
scripts/                # operational and dev scripts
docs/                   # full specification set (see below)
evals/                  # AI evaluation datasets and reports
tests/e2e/               # Playwright end-to-end tests
benchmarks/             # load-test scripts and recorded results
.github/workflows/      # CI
```

## Documentation index

| File | Contents |
|---|---|
| `docs/PRODUCT_AND_ARCHITECTURE.md` | PRD, user flows, architecture, module boundaries |
| `docs/DATA_MODEL.md` | PostgreSQL schema, constraints, state machines |
| `docs/API_CONTRACT.md` | HTTP/event/agent-tool contracts |
| `docs/SECURITY_AND_TESTING.md` | Threat model, mandatory test cases, CI gates |
| `docs/TASKS.md` | T00–T36 sequential implementation backlog |
| `docs/CODEX_OPERATOR_PROMPT.md` | Copy/paste prompts for running this backlog with an AI engineer |
| `docs/MANUAL_SETUP.md` | Human-only account/credential/billing gates (H0–H5) |
| `docs/DEPLOYMENT_RUNBOOK.md` | Local/staging/production procedures and incident runbooks |
| `docs/DEMO_AND_RESUME.md` | Demo script, benchmark/eval report templates, resume guidance |
| `docs/DECISIONS.md` | Architecture decision record |
| `docs/DEPENDENCY_DECISIONS.md` | Locked dependency versions per ecosystem |
| `docs/PROGRESS.md` | Per-ticket status, evidence and blockers (source of truth for "is it done") |

## License

No license has been selected yet for this repository (see `docs/DECISIONS.md`, ADR-006). Until one is added, all rights are reserved by the owner.
