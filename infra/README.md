# infra

`docker-compose.yml` for local PostgreSQL 16 + pgvector (`pgvector/pgvector:pg16`) and Redis 7.x (`redis:7-alpine`), see ticket **T01** in `docs/TASKS.md`. Both services bind to `127.0.0.1` only. Run via `make infra-up` / `make infra-down` / `make infra-logs` / `make infra-reset` (never `docker compose` directly) so `.env` is always loaded consistently. Status: written and offline-validated, but not yet verified end-to-end — see `docs/PROGRESS.md` (blocked on Docker being installed, Gate H0).

Cloud infrastructure-as-code lives in `infra/aws/` and is human-gated (see `docs/MANUAL_SETUP.md`, Gate H3).
