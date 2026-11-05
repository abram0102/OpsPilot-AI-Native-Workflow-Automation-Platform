# Evaluation Evidence, Recruiter Demo and Resume Material

## A. Portfolio acceptance artifacts

Produce these **only once implemented and verified**:

1. Public GitHub README with architecture, stack, user journey, real test result badges, real deployment link if present, and documented limitations.
2. Screenshots from a running seeded app: dashboard, ticket detail, knowledge ingestion status, AI trace/citations, approval inbox, published internal reply.
3. Architecture diagram including BFF/security, modular Spring API, Postgres/pgvector, Redis Streams, Python worker, cloud.
4. Short demo (3–5 minutes) with clearly synthetic sample data. No real private user/customer data.
5. Unit/integration/E2E and adversarial security test evidence: exact commands, CI run links, dates.
6. Benchmark report containing test hardware, data volume, load, baseline, improvement and raw outputs.
7. AI evaluation report containing dataset count, model ID/version, prompt version, classification accuracy, citation evaluation, blocked harmful actions, approximate costs.
8. Engineering design tradeoff article: why Spring+Python, why outbox+Redis Streams, why safe approval/checkpoints, why exact vector search before HNSW.
9. A failure/incident story where a worker is killed and recovered with exactly one visible action.
10. Optional 1–2 pilot users, with consent and synthetic data, to gather actionable feedback; do not invent customer counts.

## B. Live demo script

- **0:00–0:30:** intro/architecture. Explain product value and business problem, not just framework names.
- **0:30–1:00:** sign in as Company A owner. Show membership and private knowledge documents.
- **1:00–1:30:** create fictional shipping support ticket, show DB-backed UI.
- **1:30–2:10:** run AI agent. Show `202 QUEUED` -> worker job -> retrieval grounded in Company A docs -> trace.
- **2:10–2:50:** review immutable proposed reply; approve as admin; ticket thread receives exactly one published internal reply.
- **2:50–3:20:** switch Company B user; try Org A ticket URL and show denial, including vector isolation.
- **3:20–4:00:** show tests, real metrics and a short worker crash/recovery clip.
- **4:00–4:30:** highlight tradeoffs and planned improvements (real connectors, visual workflow builder, scaling).

## C. Real measurement templates

Record in `benchmarks/YYYY-MM-DD-report.md`:

```
Commit SHA:
Date/time and timezone:
Hardware (CPU/RAM):
Deployment (local/staging):
Dataset: orgs/tickets/chunks:
Load generator: command, VUs and duration:
Endpoint(s) and auth mode:
p50/p95/p99 latency:
Throughput requests/s:
Error rate:
CPU/memory/DB query plan:
Baseline version/results:
Optimized version/results:
Remaining limitations:
Raw output path:
```

Record in `evals/reports/YYYY-MM-DD.md`:

```
Dataset commit / split:
AI mode and provider/model:
Prompt version:
Number of cases:
Classification accuracy:
Required-approval recall:
Forbidden tool executions:
Citation precision / judged groundedness:
Median/p95 run latency:
Actual or estimated token cost (clearly specified):
Failures and example case IDs:
Test command:
```

## D. Resume bullet templates — do NOT fill in invented values

**OpsPilot | AI-Native Support Operations SaaS**
*Java 21, Spring Boot, Python, LangGraph, Next.js, TypeScript, PostgreSQL, pgvector, Redis Streams, AWS, Docker*

- Engineered and deployed a multi-tenant, AI-assisted support operations platform, implementing secure REST APIs, RBAC, PostgreSQL persistence, and tenant-isolated RAG over [N] fictional/test knowledge documents.
- Designed durable agent processing with Redis Streams, transactional outbox, persisted checkpoints, and human approval, demonstrating [actual failure-recovery result] across [N] replay tests.
- Implemented OpenTelemetry tracing, CI/CD and [N] automated tests; reduced [endpoint] p95 latency from [A] to [B] ms under [declared load] by optimizing [measured bottleneck].
- Evaluated agent behavior across [N] synthetic tasks, achieving [X]% correct classification and [Y]% correct approval gating while rejecting unauthorized tool use in the tested cases.

If real user counts, production uptime, performance metrics or model gains were not actually measured, omit the claim. A narrower truthful bullet is better than an impressive invented one.

## E. Interview discussion prompts

- What specific problem did you model and what did you intentionally leave out?
- Why is an LLM's proposed tool call not sufficient authorization?
- How do you keep Org A data from leaking to Org B in both SQL and vector search?
- Why does a queue give at-least-once delivery rather than exactly-once side effects?
- What happens if Redis publishes but Spring fails to mark the outbox row PUBLISHED?
- What happens if worker crashes after DB commit but before stream ACK?
- How is LangGraph's checkpoint related to approval state and resume? What can fail between them?
- How did you test prompt injection, hallucinated citations and unauthorized actions?
- What was the real performance bottleneck, and how did you prove your improvement?
- What would you change when scale grows 10x? Why not Kubernetes/Kafka from day one?
- What privacy/data-retention rules would you need before onboarding real customers?

## F. Milestone schedule (planning only)

| Window | Primary focus | Evidence |
|---|---|---|
| Weeks 1–2 | T00–T09 infra, auth, DB | Local services, 2-tenant auth |
| Weeks 3–4 | T10–T16 ticket/knowledge/provider | Usable full-stack flows |
| Weeks 5–6 | T17–T23 agents/approval | Reproducible paused agent demo |
| Weeks 7–8 | T24–T29 queue, reliability, E2E | Crash and security regression tests |
| Weeks 9–10 | T30–T33 evaluation, IaC/staging | Verified eval / approved deployment |
| Weeks 11–12 | T34–T36 benchmarks and polish | Real measurements, demo, release report |

This is a *work breakdown*, not a guarantee that one person or Codex will finish on this schedule. Security and infra gates may require additional time and costs.
