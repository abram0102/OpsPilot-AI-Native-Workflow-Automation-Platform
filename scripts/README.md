# scripts

Operational and developer scripts invoked by the `Makefile`. Each script is added by the ticket that needs it in `docs/TASKS.md`, not speculatively ahead of time.

- `check-infra.sh` (**T01**) — polls local Postgres+pgvector and Redis health after `make infra-up`.
