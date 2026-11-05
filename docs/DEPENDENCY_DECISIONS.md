# Dependency Decisions

Do not invent or lock patch versions before installing the actual implementation. Codex shall verify official current compatibility and record exact versions selected, installation date, lockfile location, supported runtimes, and any breaking changes.

Baseline targets:
- Java 21 LTS; Spring Boot stable compatible with Java 21.
- Node.js active LTS compatible with Next.js 16; React 19; TypeScript strict; pnpm with lockfile.
- Python 3.12; LangGraph current stable; pydantic v2 compatible libraries; `uv.lock` or equivalent.
- PostgreSQL 16 with pgvector supported by chosen image/RDS version; Redis 7.x.
- Docker engine/Compose compatible with dev machines.

Version changes should be made as explicit dependency update tickets and accompanied by smoke tests.

## T00 notes

No ecosystem dependencies were installed in T00 (no Java/Node/Python project manifests exist yet). Nothing to record here beyond the baseline targets above. T02 (Spring Boot), T03 (Python worker) and T04 (Next.js) will each record their actual selected versions, lockfile paths and installation dates when those projects are initialized.

## T01 notes — 2026-11-05

- `pgvector/pgvector:pg16` — official image maintained by the pgvector project (https://github.com/pgvector/pgvector), PostgreSQL 16 with the `vector` extension's shared library preinstalled. No digest pin yet (no registry access verified from this environment to resolve a current digest); pin to a specific digest once Docker is available and the image has been pulled and tested, per the "actively verify before locking" rule above.
- `redis:7-alpine` — official Redis 7.x image, Alpine base for small footprint. Matches `docs/DEPENDENCY_DECISIONS.md` baseline target "Redis 7.x".
- Neither image's `extversion`/exact patch version has been confirmed by actually running the container in this environment (Docker is not installed here — see Gate H0 in `docs/MANUAL_SETUP.md`). Confirm the running `vector` extension version and exact Redis version string via `scripts/check-infra.sh` / `redis-cli INFO server` once infra is started for the first time, and record them here.
