# Demo script — 4 minutes, run from a clean checkout

Every command here was run on 2026-11-13 and does what this script says it does. Nothing is
deployed, so this is a **local demo**: there is no URL to send anyone, and the script says so out
loud at 0:00 rather than hoping nobody asks.

All data is fictional. The tenants are "Acme Demo" and "Globex Demo", the customers are invented,
and the shipping policy document is written for this demo. No real person, company, ticket or
shipment appears anywhere in this repository — see [Synthetic data](#synthetic-data) below.

## Before you start (about 4 minutes, once)

```bash
cp .env.example .env
```

```bash
make infra-up
```

```bash
make seed-demo
```

`make infra-up` needs Docker. Without it, run PostgreSQL 17 (with the `vector` extension) and
Redis yourself and point `POSTGRES_*` / `REDIS_URL` in `.env` at them — everything below is
identical afterwards, and this repository was developed that way. The preflight checks are in
[`docs/DEPLOYMENT_RUNBOOK.md` §1](DEPLOYMENT_RUNBOOK.md#1-local-preflight).

Then three terminals:

```bash
make api-dev
```

```bash
make worker-dev
```

```bash
make web-dev
```

`AI_MODE=mock` is the default. **No API key is needed and no provider is called** — the mock
provider is deterministic, which is why the screenshots and the evaluation are reproducible. To run
the same demo against a real model, set `AI_MODE=real` and `LLM_API_KEY`; that spends money, and
nothing in this repository does it on its own.

---

## The script

### 0:00–0:30 — What this is

One sentence of product, one of honesty:

> OpsPilot is a multi-tenant support desk where an AI agent reads a ticket, retrieves that
> company's own knowledge, and drafts a cited reply — and cannot publish it. A human approves the
> exact text, or nothing goes out. It runs locally; it has never been deployed, and I will show you
> what that means for what I can claim.

Then the architecture diagram in [the README](../README.md#architecture), pointing at one thing:
the agent worker has no write access to the business tables. Every effect it wants goes back
through the API, which re-checks policy from scratch.

### 0:30–1:00 — Two tenants, one of them private

Sign in at <http://localhost:3000> as the Acme owner. Show **Settings → Members**: four roles,
invitation-based.

Open **Knowledge**. One document, `acme-shipping-policy.md`, status `READY`. It says a parcel is
treated as delayed after six business days — remember that line, because the agent is about to use
it, and because Globex cannot see it.

### 1:00–1:30 — A ticket

**Tickets → New ticket**:

- Subject: `Where is my order? It has not moved in six days`
- Description: `My parcel has not arrived and the tracking has not updated.`

It appears in the list immediately — this is a database row, not UI state. Reload to prove it.

### 1:30–2:10 — Run the agent

Click **Ask the agent**. The browser lands on the run page while the run is still `QUEUED`: the API
answered `202`, wrote a row and an outbox event, and returned. Nothing has been computed yet.

The page polls. Within a couple of seconds it moves `QUEUED → RUNNING → AWAITING_APPROVAL` on its
own, and the trace fills in step by step:

`CLASSIFY → RETRIEVE → DRAFT_REPLY → PROPOSE_TOOL → AWAIT_APPROVAL`

Expand `RETRIEVE`. The excerpt it pulled is from Acme's shipping policy, and the SQL that fetched it
carries the organization id as a predicate — not as a filter applied afterwards. Expand
`DRAFT_REPLY`: the proposal cites the document it actually read.

Point at the status: **`AWAITING_APPROVAL`, not `COMPLETED`**. The graph has run out of things it is
allowed to do by itself.

### 2:10–2:50 — The approval is the product

Click **Review and decide**. The proposed reply is shown in full, with its citation.

Approve it. Then open the ticket: **exactly one** agent message, and its text is the text that was
approved — not a regeneration. The run is now `COMPLETED`.

Worth saying out loud: approving twice does not publish twice. The effect is keyed by
`(run_id, tool_call_id)`, and `AgentActionIdempotencyIntegrationTest` fires ten concurrent
duplicates at it to prove one row lands.

### 2:50–3:20 — The other tenant

Sign in as the Globex user in a second browser profile. Paste the Acme ticket URL.

**No access** — and the same for the run URL, the approval URL and the document. The refusal is not
a hidden link in the UI; the server resolves membership from the database on every request and
never trusts an organization id from the client. Vector search is isolated the same way: the
embedding query itself is scoped, so Globex cannot retrieve an Acme chunk even indirectly through
the agent.

### 3:20–4:00 — Evidence, and the crash story

```bash
make test-security
```

Then the numbers, each with a file behind it:

- [`benchmarks/2026-11-13-report.md`](../benchmarks/2026-11-13-report.md) — ticket list p95
  149.93 ms → 36.91 ms, with the query plans that explain why.
- [`evals/reports/`](../evals/reports) — 60 fictional cases, 88.0% classification accuracy,
  100% approval-required recall, **0 forbidden actions executed**.
- [`docs/FAILURE_DRILLS.md`](FAILURE_DRILLS.md) — the one to tell as a story: the worker is
  `kill -9`'d immediately after an approval. Before the kill, zero agent messages on the ticket.
  Two seconds after restarting it, exactly one. Fifteen seconds later, still exactly one.

Close on the limits, because they are the interesting part: nothing is deployed, the AWS IaC has
never been applied, vector search has no HNSW index and is unbenchmarked, and the drills were run
against local processes rather than a staging environment. These are listed in the README under
[Limitations](../README.md#limitations-and-tradeoffs).

---

## Synthetic data

Everything in the seed, the fixtures and the evaluation dataset is invented for this project:

| Where | What |
|---|---|
| `make seed-demo` | Two fictional tenants ("Acme Demo", "Globex Demo"), their users and tickets |
| `tests/e2e/fixtures/acme-shipping-policy.md` | A shipping policy written for the demo |
| `evals/datasets/support_triage_v1.jsonl` | 60 fictional support cases, 10 of them adversarial |
| `benchmarks/fixture.sql` | 11,000 generated tickets with synthetic customer names |

No real customer data, no scraped tickets, no anonymised production export. The adversarial
evaluation cases contain prompt-injection text on purpose — they are the input to a safety
measurement, not instructions anything follows.

## If something does not work

| Symptom | Cause |
|---|---|
| Run stays `QUEUED` | The worker is not running. `make worker-dev`. |
| `make infra-up` fails | Docker is not running, or ports 55432/56379 are taken. |
| Sign-in loops | `.env` is missing the Auth0 values; see `.env.example`. |
| Knowledge document stuck in `PROCESSING` | The worker started before Redis; restart it. |

The operational versions of these — what to look at, and in what order — are in
[`docs/DEPLOYMENT_RUNBOOK.md` §6](DEPLOYMENT_RUNBOOK.md#6-incident-runbooks).

---

## Résumé bullets

Four bullets, each traceable to a file in this repository. Where a number would have to be
invented — users, uptime, cost saved, model accuracy against a real provider — there is no bullet.

> **OpsPilot — AI-native support operations platform** (personal project)
> *Java 21 · Spring Boot 4 · Python 3.12 · LangGraph · Next.js 16 · TypeScript · PostgreSQL +
> pgvector · Redis Streams · OpenTofu · GitHub Actions*
>
> - Built a multi-tenant support platform in which an LLM agent drafts cited replies but **cannot
>   publish**: every AI-initiated write re-validates role, tenant and approval server-side and is
>   idempotent by `(run_id, tool_call_id)`, so an at-least-once queue cannot double-publish.
> - Cut ticket-list **p95 latency from 149.93 ms to 36.91 ms (−75%)** and raised throughput from
>   **1,264 to 4,646 req/s** (30 VUs, 10k-ticket tenant, local k6) by replacing an `OR`-form keyset
>   predicate with a row-value comparison the index can use — **108× faster**, 5,000× fewer shared
>   buffers, with before/after `EXPLAIN (ANALYZE, BUFFERS)` plans recorded.
> - Evaluated agent behaviour over **60 synthetic cases including 10 adversarial prompt-injection
>   attempts**: **0 forbidden actions executed**, 100% approval-required recall, 88.0%
>   classification accuracy in deterministic mock mode.
> - Ran four dependency-failure drills against the running system and fixed what they found —
>   a readiness probe that hung for **30.0 s** with the database down (now **2.0 s**) — and proved
>   at-most-once effects by `SIGKILL`-ing the worker mid-action: **exactly one** published reply,
>   before and after restart.

**What each bullet rests on:** [benchmark report](../benchmarks/2026-11-13-report.md) ·
[evaluation reports](../evals/reports) · [failure drills](FAILURE_DRILLS.md) ·
[per-ticket evidence](PROGRESS.md).

**What is deliberately absent:** no deployment, uptime or availability figure (nothing is
deployed); no user or customer count (there are none); no claim about a real model's accuracy (the
60-case evaluation measures the deterministic mock and the prompt scaffolding around it, and
saying otherwise would be the easiest lie on this page); no cost saving.
