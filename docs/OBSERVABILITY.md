# Observability — what is measured, where to look, and what to alert on

Written at **T30** (`docs/TASKS.md`), implementing `docs/SECURITY_AND_TESTING.md` §7.

This document has an unusual rule, and it is the point of it: **every metric named here exists and
has a test.** Nothing is aspirational. Where something §7 asks for is not implemented, it is in
[Not implemented](#not-implemented) with the reason, rather than described in the present tense
where a reader would assume it works.

---

## 1. Where the numbers come from

| Surface | How to reach it | Auth |
|---|---|---|
| API metrics | `GET /actuator/prometheus` | **Bearer token required** |
| API health | `GET /actuator/health/liveness`, `/readiness` | public |
| Worker queue health | `make worker-health` (prints JSON) | local process |
| Evaluation scores | `make eval-mock` → `evals/reports/triage-*.{json,md}` | local |

### Why the metrics endpoint is not public

Exposing an endpoint under `/actuator` is not the same as making it public. `SecurityConfig`
permits only `/actuator/health/**`; everything else needs a validly signed token. That is
deliberate for two reasons: metrics name internal structure (endpoint templates, queue names,
error codes), and the run-status distribution is a business figure. A scraper therefore needs
either a service token or a network path that never reaches the internet — most simply, scrape it
on the private network and never route `/actuator/prometheus` through the public load balancer.

`MetricsEndpointIntegrationTest.theMetricsEndpointIsNotPubliclyReachable` holds this.

---

## 2. API metrics

### From Micrometer, free

Spring Boot's `http.server.requests` timer carries request rate, latency distribution and status —
everything §7 asks for under "API request rate / p50/p95 / 5xx". Nothing in this project
reimplements it, because a second source of truth for the same number is a source of disagreement.

```promql
# Request rate by endpoint template (not by URL — URLs carry ids)
sum by (uri) (rate(http_server_requests_seconds_count[5m]))

# p95 latency, non-LLM endpoints. Note §6: do not confuse this with agent run latency.
histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))

# 5xx rate as a proportion of all requests
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
  / sum(rate(http_server_requests_seconds_count[5m]))
```

> **A note on the 5xx rate.** T28 and T30 each found client errors being reported as 500 — a
> missing query parameter, a wrong verb, an unsupported content type, an unknown path. Each one
> inflated this number, which means a scanner probing the API looked exactly like an outage. If
> this metric rises, check that it is not a 4xx wearing a 5xx label before paging anyone.

### Added by this project

| Metric | Type | Meaning |
|---|---|---|
| `opspilot_agent_runs{status="…"}` | gauge | Runs currently in each status. One series per status, so a dashboard can stack or filter, and a new status needs no new metric name. |
| `opspilot_approvals_oldest_pending_seconds` | gauge | Age of the **oldest** pending approval. `0` when none are pending. |
| `opspilot_outbox_events{status="PENDING"}` | gauge | Outbox rows waiting to publish. A rising value means Redis is unreachable or the publisher is stopped. |
| `opspilot_outbox_events{status="DEAD"}` | gauge | Dead-lettered rows. **Should be zero.** Anything here needs an operator (see the runbook). |

Why the **oldest** approval rather than a mean: the question is "is anything waiting too long", and
a mean hides exactly that — nineteen fresh approvals and one three-day-old one average out to
something that looks fine.

These are gauges read from the database rather than counters incremented at write sites. A counter
needs an increment at every path that changes the value, every new path is a chance to miss one,
and a missed increment is invisible: the metric just reads low forever. Reading the database cannot
drift from it. Each value is cached for 15 seconds so a misconfigured scraper cannot turn
monitoring into load.

---

## 3. Worker queue metrics

`make worker-health` prints:

```json
{"stream": "opspilot:events", "group": "agent-workers", "streamLength": 17,
 "deadLetterLength": 0, "pending": 0, "lag": 0, "consumers": 9,
 "oldestPendingIdleMs": null}
```

| Field | Meaning |
|---|---|
| `streamLength` | Entries in the stream |
| `pending` | Delivered but not acknowledged |
| `lag` | Entries the group has never seen. `null` on older Redis — unknown, not zero |
| `oldestPendingIdleMs` | **Backlog age**: how long the longest-waiting entry has gone without progress. `null` when nothing is pending |
| `deadLetterLength` | Entries that exhausted their attempts |

`oldestPendingIdleMs` is the one worth watching. Depth alone cannot distinguish a queue of two that
has not moved in an hour from a queue of two hundred being worked through briskly — and the first
is the worse state.

`null` rather than `0` when nothing is pending is deliberate throughout: zero reads as "we measured
it and nothing is waiting", and an alert threshold sits just above zero.

---

## 4. Suggested alerts

Thresholds are **starting points**, not measured SLOs — this project has no production traffic to
derive them from, and inventing a number and calling it a target would be the kind of claim
`AGENTS.md` forbids. Tune them against your own baseline.

| Alert | Condition | Why |
|---|---|---|
| API unhealthy | `/actuator/health/readiness` not `UP` for 1 min | The load balancer's own signal |
| Elevated 5xx | 5xx ratio > 2% for 5 min | §7. Check for 4xx-as-5xx first |
| Slow API | p95 > 500 ms for 10 min | §6's stretch objective, non-LLM endpoints only |
| **Dead letters** | `opspilot_outbox_events{status="DEAD"} > 0` | Should never be non-zero. Operator action required |
| Outbox not draining | `opspilot_outbox_events{status="PENDING"}` rising 10 min | Redis unreachable, or the publisher is not running |
| Approval ageing | `opspilot_approvals_oldest_pending_seconds > 86400` | A customer is waiting on a human who has not looked |
| Runs stuck | `opspilot_agent_runs{status="RUNNING"}` flat and non-zero 15 min | A worker died holding leases |
| Queue backlog age | `oldestPendingIdleMs > 300000` | Entries delivered and never acknowledged |
| Failure spike | `opspilot_agent_runs{status="FAILED"}` rising | Provider outage, or a bad prompt revision |

---

## 5. Correlation IDs

| Id | Where | How |
|---|---|---|
| `requestId` | Every API log line | `RequestIdFilter` puts it in the MDC and echoes `X-Request-Id`; a caller-supplied header is reused rather than replaced, so a client can correlate too |
| `requestId` | Every API error body | `ApiErrorResponse.requestId` — the id a user can quote is the id in the logs |
| `runId` | Agent traces | `agent_steps` rows, and the run explorer renders them |
| `eventId`, `toolCallId` | Outbox and dead-letter records | Identifiers only, never payloads |

`RequestIdFilterTest` covers generation, reuse of an inbound header, and MDC cleanup even when the
handler throws.

---

## 6. What the evaluation measures

`make eval-mock` writes a scored report to `evals/reports/`. The Markdown is the one to read; the
JSON is the one to diff. Its scored sections are reproducible — two mock-mode runs are byte
identical apart from timestamps and durations, asserted by
`test_triage_eval.py::test_two_runs_produce_identical_scores`.

The number that gates a release is **replies published without a human approval**, which must be
0.0%. See `evals/README.md` for the metric definitions and why proposals and executions are counted
separately.

---

## 7. Optional error reporting

`SENTRY_DSN` enables Sentry in the worker. Unset — the default, and the only state this repository
is tested in with a real destination — nothing is initialised and `sentry-sdk` is not even a
dependency.

When enabled, two flags are forced off and tested:

- `send_default_pii=False` — otherwise request headers, cookies and user identifiers are attached
  to every event, which is precisely what `AGENTS.md`'s "a trace never logs access tokens, API
  keys, raw cookies or unnecessary sensitive personal data" forbids.
- `include_local_variables=False` — a traceback's locals routinely hold the ticket text and
  document excerpts being processed. An error report is not a place for a tenant's support ticket.

Setting a DSN without the SDK installed **raises at startup** rather than continuing quietly: the
failure mode worth preventing is an operator believing errors are reported when they are not.

---

## Not implemented

Stated here rather than left to be discovered.

- **Distributed tracing / OTLP export.** §7 names OpenTelemetry. Correlation ids and metrics are
  implemented; span export to a collector is not. It needs two dependencies and a collector to
  verify against, and this environment has neither — wiring an exporter that has never sent a span
  would be exactly the "working integration" `AGENTS.md` forbids inventing. `OTEL_EXPORTER_OTLP_ENDPOINT`
  exists in `.env.example` and is currently read by nothing.
- **Worker metrics in Prometheus format.** The worker reports queue health as JSON through
  `make worker-health`. It has no `/metrics` endpoint, so the queue alerts above have to be driven
  from that JSON rather than scraped. The API's metrics are scrapeable; the worker's are not.
- **Token and cost figures in mock mode.** Null, always, and deliberately — no provider is called,
  and zero would read as a measurement. Real figures need Gate H2 (`docs/MANUAL_SETUP.md`).
- **Latency figures that mean anything.** The evaluation's p50/p95 in mock mode measure the
  harness's own overhead, which is microseconds and says nothing about a provider. They are
  recorded so a regression in the harness is visible, not as a performance claim. Real API latency
  under load is `k6` and T31's job.
- **Dashboards as code.** The queries above are written out rather than shipped as a Grafana JSON
  model, because an untested dashboard file is a maintenance burden that looks like a deliverable.
