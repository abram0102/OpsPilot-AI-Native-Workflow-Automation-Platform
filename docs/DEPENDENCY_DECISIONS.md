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
