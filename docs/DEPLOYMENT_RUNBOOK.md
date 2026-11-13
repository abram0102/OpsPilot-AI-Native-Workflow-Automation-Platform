# Local Development, Staging, Production and Incident Runbook

**Status:** Planned commands and architecture. The blueprint does NOT contain the implemented app. Codex must create the scripts and verify every documented command before marking it working.

## 1. Local preflight

- Workstation: Git, Docker Desktop/Compose, Node LTS compatible with Next.js 16, `pnpm`, JDK 21, Python 3.12, Maven Wrapper, Python dependency manager (`uv`).
- Check: `git --version`, `docker compose version`, `node -v`, `pnpm -v`, `java -version`, `python3 -V`.
- `cp .env.example .env` and replace local-only placeholder values; never add `.env` to Git. AI mode should default to `mock` in local and CI.
- `make infra-up` starts local Postgres+pgvector and Redis; `make infra-logs` for diagnosis. On first Spring Boot start, Flyway migrates DB; it is not necessary to create tables manually.
- Planned service commands (Codex finalizes actual paths and scripts):
  - Terminal 1: `make infra-up`
  - Terminal 2: `make api-dev`
  - Terminal 3: `make agent-dev`
  - Terminal 4: `make worker-dev` — the Redis Streams consumer (T25). `agent-dev` serves health and queue depth only; **this** is the process that runs ingestion and agent runs, so without it events accrue on the stream and nothing happens.
  - Terminal 5: `make web-dev`
  - Browser: `http://localhost:3000`, backend health `http://localhost:8080/actuator/health`, worker health `http://localhost:8000/health/live`, queue depth `http://localhost:8000/health/queue` or `make worker-health`.
  - The API's own publisher is off unless `OUTBOX_PUBLISHER_ENABLED=true` is in `.env` (T24, ADR-048) — `make api-dev` loads `.env`, and `.env.example` ships it on.
- `make seed-demo`: seed *fictional* tenants, users, tickets and knowledge docs; deterministic seed idempotent and local/test only. Never allow it on staging/prod without explicit override.
- `make check`: fast reproducible static+unit+integration+contract+mock-eval; no paid API key.
- `make test-e2e`: test OIDC signed fixture users, two tenants, mock AI, Playwright browser E2E.

## 2. Database migrations

- Use Flyway migrations committed with code. On production, migration runs as separate controlled job using narrowly scoped credentials, before application rollout; don't run many service replicas racing schema changes.
- Changes should be backward-compatible across rolling deploy where practical: expand -> deploy -> backfill -> contract in separate migration.
- Verify `flyway validate` and snapshot/backup before destructive migration. Test restore on staging.
- For vector extension on AWS RDS, check target engine+extension availability, role privileges and embedding dimension prior to launch.
- Never use `DROP SCHEMA`, `hibernate.ddl-auto=update`, or auto-reset in production.

## 3. Environment matrix

| Feature | Local | CI | Staging | Production |
|---|---|---|---|---|
| Database | Docker Postgres | Testcontainers | Private RDS | Private RDS with backups |
| Redis | Docker Redis | Testcontainers | Private Redis | Private Redis w/ monitored persistence policy |
| User login | Auth0 dev or signed test issuer | Signed test issuer only | Auth0 configured | Auth0 configured |
| AI | mock default | mock | real only by explicit budget approval | real with spend limits |
| Object storage | safe local adapter | temp bucket/local adapter | private S3 | private S3 |
| Secrets | ignored `.env` | ephemeral secrets | AWS Secrets Manager | AWS Secrets Manager |
| Deployment | localhost | isolated test jobs | protected GitHub environment | approved protected release |
| Alerts | logs | test reports | CloudWatch/Sentry | CloudWatch/Sentry + on-call policy |

## 4. AWS proposed architecture

```
Internet -> HTTPS / ALB -> Next.js web service (ECS or managed web host)
                          -> Spring API service (private network service)
Private subnet: Spring API -> RDS PostgreSQL (with pgvector)
                           -> Redis Streams
                           -> S3 signed upload orchestration
Private subnet: Python AI worker -> Redis + RDS read/checkpoint + internal API
GitHub Actions --OIDC temporary credentials--> ECR / ECS deploy
Logs/traces/alerts -> CloudWatch + optional Sentry
```

- Make it impossible to access RDS or Redis directly from internet. ALB exposes only intended public routes; internal agent action endpoint only from worker network plus machine identity.
- HTTPS everywhere public, least privilege task IAM, per-environment secrets, private buckets blocking public access. Do not store database credentials in Docker images or source.
- Avoid overprovisioning: use the smallest measured service sizes and autoscaling based on CPU/queue age as needed. Produce a human-reviewed cost plan.
- Do not deploy or incur charges automatically based on these docs.

**Implemented at T31** in `infra/aws/` as OpenTofu (not HashiCorp Terraform — see that directory's
README for why, and note the HCL runs unchanged under either). `make iac-validate` format-checks,
validates and lints it and **makes no AWS calls**; `make iac-plan` deliberately refuses and prints
the command for you to run yourself once Gate H3 is open. Nothing in the repository runs `plan` or
`apply`.

The configuration has never been applied. Validate and lint prove it is well-formed and that every
argument matches the provider schema; they do not prove it applies. `infra/aws/README.md` has the
Gate H3 checklist, the state-backend block to paste in, the cost drivers ranked, and the known
gaps.

## 5. Deployment procedure (post implementation)

1. Confirm CI green, secret scan clean, threat-model/regression checks green, current eval baseline recorded.
2. Human reviews Terraform plan and estimated spend; checks database backup and potential migration impact.
3. Build signed/versioned images with commit SHA and image digest; publish to ECR via approved GitHub OIDC environment.
4. Execute compatible DB migration job once; validate database extension and schema version.
5. Deploy worker first if backward-compatible, then API, then web as appropriate to dependency compatibility; exact order documented by release.
6. Health/readiness smoke tests; login; create synthetic ticket; upload document; run mock agent; require approval; verify one internal reply; test separate tenant denial.
7. Watch p95 latency, errors, queue lag, failed runs, budget, Sentry for at least the formal release observation period.
8. On failure, stop rollout and revert to previous image/task definition; if schema expanded and data changed, apply forward-compatible fix rather than destructive rollback.

## 6. Incident runbooks

### Queue lag or Redis unavailable

- Symptoms: queue depth and oldest outbox age rising; `QUEUED` runs remain waiting.
- Actions: verify Redis network/health, consumer group pending count, worker logs/leases; do not repeatedly duplicate publish manually. Recover Redis, let outbox retry; inspect idempotency and reclaimer metrics.
- Verification: previously queued test run reaches terminal state once; no duplicate published messages.

### Worker crash mid-action

- Verify `tool_executions` unique key and ticket message count before replay.
- Restart worker, wait for lease expiry/reclaim; resumed state must load persisted checkpoint. Audit confirms action executed at most once.

### Agent stuck at approval

- Verify `PENDING` approval, checkpoint existence, run's `AWAITING_APPROVAL`, decision event in outbox.
- Do NOT bypass approval by editing DB. Fix outbox or worker idempotently, or ask approver to take action.

### Suspicious tenant boundary violation

- Suspend affected ability to run tools and rotate compromised secrets. Preserve sanitized audit and request traces; verify whether issue is API, retrieval, or storage policy. Do not publish further data. Treat as a release blocker.

### AI spend spike

- Disable live provider via feature flag, keep mock mode for tests, inspect per-run token limits, rate limit, retries and duplicate event handling. Notify operator, do not silently exceed budget.

### Migration failure / DB outage

- Stop rollout; inspect Flyway current/failed version, restore RDS snapshot only after human approval and business impact review. Do not run one-off ad hoc destructive SQL.

## 7. Operational ownership and observability

- Set expected objectives for key endpoints after measurement. Distinguish HTTP 'run accepted' from asynchronous 'run completed'.
- Keep structured logs with requestId/runId/eventId/toolCallId. No email bodies, API keys, cookies, complete prompts or entire document text in general logs.
- Keep backup/restore procedure and retention policy current; test restoration at least once before public beta.
- Runbook, alerts, incident report template and documented on-call owner are part of production readiness, even for a solo portfolio project.
