# Release report — release candidate, 2026-11-13

Ticket **T36**. This is the final audit of the V1 build. Everything below was run on the date
above against commit `82bf5d8` plus the T36 changes; where something could not be run, it says so
and why, rather than being left to read as a pass.

## The headline, stated once and plainly

**This is not production ready, and this report does not claim it is.** Three release gates are
incomplete by construction:

- **Nothing is deployed.** The AWS infrastructure is written and statically validated; no `plan`
  and no `apply` has ever run, and no AWS account is attached to this project.
- **Backup and restore has never been executed.** The procedure is written. A restore that has
  not been performed is not a backup you have.
- **No real model has ever been called.** The default provider is a deterministic mock and
  always has been. The adapter is contract-verified offline only; `make eval-real` refuses with
  five named missing items until Gate H2 is opened, and the evaluation measures the mock.

What it *is*: a complete, locally runnable, evidence-backed system whose security and reliability
properties are tested rather than asserted. The distinction matters, and the rest of this document
keeps it.

---

## 1. Definition of Done — per clause

The release criteria are §9 of [`docs/PRODUCT_AND_ARCHITECTURE.md`](PRODUCT_AND_ARCHITECTURE.md).

| # | Criterion | Verdict | Evidence |
|---|---|---|---|
| 1 | Two-tenant isolation tests pass | **PASS** | `SecurityRegressionSweepIntegrationTest` enumerates every mapped route and probes it unauthenticated and cross-tenant; 4 Playwright isolation tests; composite FKs carry `organization_id`. Full Java suite 265/265. |
| 2 | API returns typed responses and authorization errors | **PASS** | Structured JSON with stable codes; `GlobalExceptionHandler` covers 400/401/403/404/405/409/413/415. OpenAPI generated from the running app, drift-checked in CI. |
| 3 | Agent mock **and real-mode** smoke tests pass | **FAIL (real half)** | Mock: 355/355 Python tests, 60-case evaluation, all green. Real: **never run.** T20 is recorded BLOCKED for exactly this reason — no key exists here, a paid call needs the operator's explicit authorization under AGENTS.md §15, and `make eval-real` refuses with exit code 2 until Gate H2 is opened. The adapter is verified offline via `MockTransport`, which proves the request and response contract and nothing about a model. |
| 4 | All risky publishing needs approval | **PASS** | Policy gate in Python *and* an independent re-check in Java; `InternalAgentActionHttpIntegrationTest` (26 cases) rejects publication without, with fabricated, pending, rejected and mismatched approvals. |
| 5 | Double submission has one effect | **PASS** | Unique `(run_id, tool_call_id)`; `AgentActionIdempotencyIntegrationTest` fires ten concurrent duplicates and asserts one row. Drill 3 confirms it operationally after `kill -9`. |
| 6 | Restart recovery tests pass | **PASS** | Checkpoint resume across a fresh process; outbox crash-window republish; [drills 1–3](FAILURE_DRILLS.md) observed, not assumed. |
| 7 | Production deployment is documented | **PASS (documented), NOT EXECUTED** | [`docs/DEPLOYMENT_RUNBOOK.md`](DEPLOYMENT_RUNBOOK.md), [`infra/aws/README.md`](../infra/aws/README.md), two workflows. Never run. |
| 8 | Observability captures trace IDs and failed work | **PASS** | Request ids in logs, business gauges, dead-letter stream with a replayable terminal state. Gaps named in [`docs/OBSERVABILITY.md`](OBSERVABILITY.md). |
| 9 | Evaluation and load tests produce archived actual outputs | **PASS** | [`evals/reports/`](../evals/reports) and [`benchmarks/`](../benchmarks) hold raw JSON and written reports with environment and limits. |
| 10 | README/demo can be followed by a third party | **PASS — verified by doing it** | A clean clone of `82bf5d8` into an empty directory, `.env` supplied, brought up API + worker + web and passed **9/9 E2E tests**. See §4. |
| 11 | No insecure public auth bypass | **PASS** | The test issuer is refused unless explicitly enabled and fails startup under the `production` profile; `ProductionSafetyGuard` also refuses both committed development secrets. |

**Release-blocking invariants: all pass.** Clauses 3 and 7 are incomplete against gates that
require money and a cloud account, not against the code — but note the asymmetry between them:
clause 7's deployment is written and never executed, while clause 3's real-model half has never
run at all. Neither has been demonstrated; only one of them has ever been attempted.

## 2. Full test matrix — run for this report

| Suite | Command | Result |
|---|---|---|
| Java (unit + Testcontainers-equivalent, external Postgres/Redis) | `./mvnw test -Dopspilot.test.external-*` | **265 passed**, 0 failures, 0 errors |
| Python | `uv run pytest` with Postgres + Redis | **355 passed**, 0 skipped |
| Web unit | `pnpm test` (Node 26) | **54 passed** |
| End-to-end (includes the accessibility pass) | `make test-e2e` | **11 passed** |
| Accessibility | part of `make test-e2e` | **2 passed**, 0 violations at any impact |
| Security sweep | part of the Java suite | included above |
| Evaluation | `make eval-mock` | **PASS** — 60 cases, 0 forbidden executed, 100% approval recall, 88.0% accuracy |
| Lint (all languages + links + workflows) | `make lint` | clean; 66 internal links across 49 files resolve |
| Threat model references | `scripts/check-threat-model.py` | 101 referenced tests all exist |
| Dependency advisories | `make audit-deps` (OSV) | **No issues found** across 674 packages |
| Secret scan | `gitleaks detect` over **all 87 commits** | **no leaks found** |

**CI on the last pushed commit** (`82bf5d8`):
[run #43477911694](https://github.com/abram0102/OpsPilot-AI-Native-Workflow-Automation-Platform/actions/runs/43477911694)
— all 9 jobs green.

One note on reproducibility worth recording: the evaluation was re-run a day after T30's report and
the scored sections are **byte-identical** apart from wall-clock latency. The determinism claim is
tested, not asserted.

## 3. Credential audit

- `gitleaks detect` over the full history (87 commits, 3.02 MB): **no leaks**.
- No `.env`, `.pem`, `.key`, `id_rsa`, `.p12` or credentials file has ever been added in any
  commit (checked with `git log --all --diff-filter=A`). Only `.env.example`, which holds
  placeholders.
- `.dockerignore` at the root and per service excludes `**/.env.*`, added at T32 after finding
  that an image build would otherwise have baked a real Auth0 secret into a layer.
- Production secrets are specified to live in AWS Secrets Manager; GitHub Actions deployment uses
  short-lived OIDC credentials with no long-lived keys. **Written, never exercised.**
- The traces assert their own hygiene: `SecretHygieneIntegrationTest` captures logs at TRACE and
  fails if a bearer token, cookie or API key appears.

## 4. Clean-checkout smoke — the one that could have failed

```
git clone <repo> /tmp/.../opspilot     # fresh directory, nothing shared with the working copy
cp .env.example .env                    # then local Postgres/Redis values supplied
E2E_POSTGRES_DB=opspilot_smoke_test make test-e2e
```

Result: Maven resolved its own dependencies, pnpm installed on first run without being asked,
Flyway migrated an empty database, the seeder created both tenants, the worker attached to the
stream, Next.js built and served, and **all 9 end-to-end tests passed** — agent run, approval,
publication, knowledge ingestion, tenant isolation and ticket CRUD.

What this does **not** prove: the `make infra-up` Docker path, which cannot be run here (Gate H0,
no Docker). A third party with Docker follows one fewer manual step than this smoke did, so the
risk is low, but it is untested and is listed below as such.

## 5. UX and accessibility smoke

axe-core 4.12.1 against WCAG 2.1 A and AA on the dashboard, ticket list, knowledge page, ticket
detail and sign-in page.

**One real finding, fixed:** the table column headers used `--muted` on `--surface-sunken`, which
measured **4.484:1** against the 4.5:1 that AA requires for normal text — a failure by a hair,
and a failure. The token was darkened to `#55686f` (5.10:1 on sunken, 5.78:1 on the card surface);
dark mode already passed at 7.31:1. Re-run: **no violations at any impact level.**

The check is now `tests/e2e/tests/a11y.spec.ts`, in the default suite, injecting axe from
`node_modules` so it runs offline.

**What this does not claim.** axe catches the machine-checkable minority of accessibility
problems. Nobody has driven this product with a screen reader or completed a keyboard-only pass,
and no user with a disability has tried it. "No axe violations" is a floor.

## 6. Non-blocking issues, with severity

| Sev | Issue | Where | Note |
|---|---|---|---|
| **High** | Backup/restore never executed | [`FAILURE_DRILLS.md`](FAILURE_DRILLS.md#backup-and-restore--written-never-tested) | Blocks any real data. Not blocking a local release candidate. |
| **High** | Dependabot #4 would break the worker | PR #4 | Bumps the image to Python 3.15 against `requires-python = ">=3.12,<3.13"`. **Must not be merged as-is**; needs a deliberate interpreter upgrade. |
| **Medium** | `AgentRunRepository.findPageAfter` has the OR-shape that T34 proved slow | [`benchmarks`](../benchmarks/2026-11-13-report.md#remaining-bottlenecks-and-limits) | Fix known and measured for the sibling query; deliberately unshipped without its own benchmark. |
| **Medium** | Vector search unbenchmarked, no HNSW index | `DATA_MODEL.md` | Exact search is correct and fine at demo volume; it is the likeliest next bottleneck. |
| **Medium** | Real-provider path exercised once | T20 | Costs money per run; no regression coverage. |
| **Medium** | Docker bring-up path untested locally | §4 | Gate H0. CI exercises service containers, not `infra/docker-compose.yml`. |
| **Low** | Dependabot #1, #2, #3, #5, #6 open | PRs | Routine bumps: TypeScript 6, `@types/node` 26, FastAPI patch, Vite plugin, Next group. None carries a known advisory (OSV clean). |
| **Low** | Connection-pool sizing never investigated | T34 | Defaults, plus T33's 2 s connection timeout. |
| **Low** | `opspilot_api_test` database left with a non-Flyway schema | this machine only | A local artefact of a mistaken test run; no repository impact. The Java suite uses `opspilot_java_test`. |
| **Low** | Dev database `opspilot` lacks `flyway_schema_history` | this machine only | Documented for the operator since T27; not a repository defect. |

## 7. Thirty-day operations plan

This assumes the deployment gate is opened. Until it is, items marked **(gated)** cannot start.

**Week 1 — make it real, carefully.**
1. **(gated)** Open Gate H3: AWS account, six repository variables, a `staging` environment with a
   required reviewer. Run `tofu plan` and read every line before any apply.
2. **(gated)** Apply to staging only. Confirm the internal ALB is genuinely internal and the
   database has no public address.
3. **(gated)** **Restore a backup before trusting one.** Take a snapshot, restore it to a new
   instance, point a throwaway stack at it and run the E2E suite against the restored data. Until
   this passes, treat the system as having no backup.
4. Merge the five safe Dependabot PRs; close or rework #4 with an explicit interpreter decision.

**Week 2 — watch it.**
5. **(gated)** Wire the alarms named in [`OBSERVABILITY.md`](OBSERVABILITY.md) and then *fire each
   one deliberately* — an alarm that has never fired is a configuration, not an alarm.
6. **(gated)** Re-run the T34 benchmark on staging, where the load generator is not sharing a
   laptop with the database, and re-judge the "p95 under 500 ms" objective there.
7. **(gated)** Re-run drills 1–4 against staging infrastructure rather than local processes, and
   add RDS failover and ElastiCache failover, which have never been tested.

**Week 3 — the model.**
8. Open Gate H2 with a funded key and a hard spend cap. Run the 60-case evaluation against a real
   model and publish the numbers beside the mock ones; expect them to be worse and say so.
9. Add a scheduled real-provider smoke with a budget, so the real path stops being untested
   between demos.
10. Decide on HNSW with a measurement: index, compare recall against exact search, keep whichever
    the numbers favour.

**Week 4 — close the loop.**
11. Fix `AgentRunRepository.findPageAfter` with its own benchmark attached.
12. Keyboard-only and screen-reader pass over the main flows; axe is the floor, not the ceiling.
13. Write the first real incident review, even for a drill, and check the runbook actually matches
    what the operator needed.

## 8. Roadmap beyond thirty days

**Near** — external email publishing behind its own approval and authorization gate; Slack and
Gmail connectors; per-tenant prompt and policy configuration; an approval inbox with assignment
and SLA; retrieval quality work (reranking, chunk-boundary tuning) measured against the evaluation
set rather than by taste.

**Further** — custom roles; multi-region; Kafka *if and when* Redis Streams is shown by
measurement to be the constraint, which it currently is not; a visual workflow designer, which
the spec explicitly defers; SOC-2-shaped audit exports.

**Deliberately not planned** — autonomous external action without approval, automatic refunds, a
general tool executor, or training a model. The first is the product's whole premise inverted; the
last three are other people's products.

## 9. Build and deploy logs

| What | Where |
|---|---|
| CI for the audited commit | [run #43477911694](https://github.com/abram0102/OpsPilot-AI-Native-Workflow-Automation-Platform/actions/runs/43477911694) |
| Per-ticket evidence, including every failed first attempt | [`docs/PROGRESS.md`](PROGRESS.md) |
| Benchmark raw output | `benchmarks/results/` |
| Evaluation raw output | `evals/reports/` |
| Deploy logs | **none exist** — nothing has been deployed |

## 10. Release tag

**Not tagged.** Tagging is a human gate (AGENTS.md §15, T36). When the operator wants one, the
candidate is the commit this report audits, and the tag message should carry the same sentence
this report opens with: the security, E2E and evaluation gates pass; the deployment, backup and
real-model gates do not.
