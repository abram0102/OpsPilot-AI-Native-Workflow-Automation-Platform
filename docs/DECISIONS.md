# Architecture Decision Record

## ADR-001 — Modular Java backend plus separate Python AI worker

Status: **Accepted as blueprint decision**. Reason: demonstrate business service engineering and specialized AI orchestration without creating artificial microservices. Tradeoff: inter-service contracts and dual-language testing.

## ADR-002 — Postgres as source of truth, Redis Streams as event transport

Status: **Accepted as blueprint decision**. Every critical business write and outbox insertion is transactional in Postgres; workers are idempotent. Tradeoff: outbox/lease logic requires deliberate testing; exactly-once delivery itself is not claimed.

## ADR-003 — Approval is enforced by deterministic server policy

Status: **Accepted as blueprint decision**. Agent text and tool proposals are untrusted. Only specific server-authorized actions run; publishing a reply requires approved immutable proposal. Tradeoff: less autonomous but safer and easier to reason about.

## ADR-004 — Auth0 for production identity; offline signed fixture for tests

Status: **Accepted as blueprint decision**. Avoid reinventing password/session security. Tradeoff: manual identity-provider setup; tests need isolated signed fixtures that never run in production.

## ADR-005 — Exact tenant-scoped vector search first

Status: **Accepted as blueprint decision**. Small datasets benefit from simpler correctness baseline. HNSW is a future measured optimization, given tenant filtering can affect approximate recall.

## ADR-006 — No LICENSE file committed in T00

Status: **Accepted, pending owner decision**. `AGENTS.md`/T00 only calls for a LICENSE "placeholder only if rights are clear." The repository owner has not yet chosen a license (e.g. MIT vs. all-rights-reserved for a private portfolio repo), so none is added in T00 to avoid asserting terms the owner has not approved. Add a real `LICENSE` file in a later ticket once the owner picks one; until then the repository defaults to all-rights-reserved.

## ADR-007 — No Postgres bootstrap/init SQL in T01; Flyway alone owns `CREATE EXTENSION vector`

Status: **Accepted**, 2026-11-05. `docs/TASKS.md` T01 says to "create bootstrap SQL only for extensions if required." The `pgvector/pgvector:pg16` image ships the extension's shared library already available to any database on that instance, and `CREATE EXTENSION vector` does not need `shared_preload_libraries` or a superuser-only init step beyond what a normal migration role can run. So `infra/docker-compose.yml` mounts no `docker-entrypoint-initdb.d` scripts; the extension is created exactly once, by the Flyway `V001__extensions_and_core.sql` migration in T06, matching `docs/DATA_MODEL.md`'s migration sequence and avoiding two different places that could both try to create the same extension. Tradeoff: a developer who only runs `make infra-up` without ever starting `services/api` (and therefore never running Flyway) will see pgvector reported as "not created yet" by `scripts/check-infra.sh` — this is expected and documented in the script's own output, not a bug.

Further decisions and deviations must be recorded here with date, context, alternatives, rationale and consequences.
