# OpsPilot — Codex Build Blueprint

**Purpose:** Build a demonstrably production-minded, AI-native support-operations platform. This is a specification and implementation plan, **not** a finished application. The files below are designed to be placed in a new Git repository and read by Codex one ticket at a time.

## How to use this pack

1. Create a fresh, empty Git repository named `opspilot`; put all files from this pack in the repository root. Commit the planning baseline.
2. Read `docs/PRODUCT_AND_ARCHITECTURE.md`, `docs/DATA_MODEL.md`, `docs/API_CONTRACT.md`, `docs/SECURITY_AND_TESTING.md`, and `docs/MANUAL_SETUP.md` once. `AGENTS.md` gives Codex mandatory instructions.
3. Open the repository with Codex and paste **`docs/CODEX_OPERATOR_PROMPT.md`** (the complete contents) into your Codex session. Ask it to execute **T00 only**.
4. Review the implementation summary, tests, and the final diff. Do not let Codex start T01 before T00 passes its acceptance checks. Then ask: `Implement T01 as defined in docs/TASKS.md, following AGENTS.md. Stop after T01.`
5. Repeat through T36. Handle explicit **[HUMAN GATE]** steps in `docs/MANUAL_SETUP.md` when the corresponding ticket requires them. Do not commit real credentials.
6. Use `docs/DEMO_AND_RESUME.md` to create the evidence and presentation package.

## Project design defaults

- Core use case: AI-assisted customer support ticket triage, knowledge-grounded reply drafting, and human approval for risky publishing.
- Stack: Next.js 16 / React 19 / TypeScript; Java 21 / Spring Boot; Python 3.12 / LangGraph; PostgreSQL 16 + pgvector; Redis Streams; Auth0 OIDC; AWS S3 / ECS Fargate / RDS.
- AI execution: bounded steps, explicit allowlisted tools, safe mock provider for automated tests, versioned prompts and eval fixtures.
- Backend service owns business API, authorization, and externally visible state transitions. Worker may read tenant-scoped knowledge and write run/checkpoint artifacts; business mutations are via a dedicated internal action API with strict authorization.
- Safety: tool calls are **proposals**, not authority. A server-side policy determines which actions require approval. Never send real email, charge money, or contact third parties without user-authorized integration and approval.
- Architecture: one repository; four runtime deployables at most (web, backend, AI worker, outbox dispatcher integrated into backend); no Kubernetes initially.

## Non-goals for version 1

- A general visual DAG designer; full CRM; payment processing; automatic refunds; autonomous external emailing; dozens of agents; a self-hosted foundation model; Kubernetes; Kafka; enterprise SSO; uncontrolled scraping; training a new LLM.

## Index

| File | Contents |
|---|---|
| `AGENTS.md` | Mandatory Codex engineering contract |
| `docs/PRODUCT_AND_ARCHITECTURE.md` | PRD, user flows, architecture, boundaries, repository layout |
| `docs/DATA_MODEL.md` | PostgreSQL tables, constraints, indices, migrations, state machines |
| `docs/API_CONTRACT.md` | Public/internal endpoints, schemas, permissions, error examples |
| `docs/SECURITY_AND_TESTING.md` | Threat model, test cases, gates, metrics, evaluation design |
| `docs/THREAT_MODEL.md` | Threat-to-test map; `scripts/check-threat-model.py` fails CI if a referenced test is renamed |
| `tests/e2e/README.md` | How the Playwright suite signs in offline and what it covers (`make test-e2e`) |
| `docs/TASKS.md` | T00–T36 sequential implementable tickets, deliverables and acceptance |
| `docs/CODEX_OPERATOR_PROMPT.md` | Copy/paste Codex prompt and session contract |
| `docs/MANUAL_SETUP.md` | Human-only account, credential, payment, and deployment gates |
| `docs/DEPLOYMENT_RUNBOOK.md` | Local, staging, production, recovery, operations |
| `docs/DEMO_AND_RESUME.md` | Demo script, benchmark record, README, resume bullets |
| `.env.example` | Safe placeholders; no real secrets |

## Source of truth and change policy

1. `AGENTS.md` policies outrank implementation details in other local files.
2. Task-specific acceptance checks in `docs/TASKS.md` are mandatory.
3. Backend OpenAPI must be generated from implementation and committed; do not invent an API in frontend that differs from the server.
4. Any departure from the specified architecture needs a one-paragraph ADR, a migration plan, and an explanation of why it simplifies the outcome.
5. Treat source references as orientation; inspect official current documentation before locking versions or copying snippets.

## Official reference URLs

- Next.js auth: https://nextjs.org/docs/app/guides/authentication
- Next.js production: https://nextjs.org/docs/app/guides/production-checklist
- Spring Security JWT: https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html
- LangGraph HITL: https://docs.langchain.com/oss/python/langgraph/interrupts
- pgvector: https://github.com/pgvector/pgvector
- AWS RDS PostgreSQL extensions: https://docs.aws.amazon.com/AmazonRDS/latest/PostgreSQLReleaseNotes/postgresql-extensions.html
- GitHub Actions OIDC: https://docs.github.com/en/actions/concepts/security/openid-connect
- OWASP GenAI: https://genai.owasp.org/
