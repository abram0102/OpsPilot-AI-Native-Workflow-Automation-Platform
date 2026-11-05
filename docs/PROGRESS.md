# Implementation Status

**Planning baseline only — no implementation tickets have been completed.** Codex must update status and evidence after *each* ticket.

| Ticket | State | Commit | Tests / evidence | Blockers |
|---|---|---|---|---|
| T00 | DONE (uncommitted) | — | `make help` exits 0 and lists all targets; every other `make` target exits 1 with a clear "not implemented yet, see ticket Txx" message (verified for `infra-up`); `git status --porcelain` shows only the files added by this ticket, no binaries/credentials; internal doc cross-references resolve to real files | None for T00 itself. Not yet `git commit`/pushed — AGENTS.md rule 19 requires explicit operator request before committing. |
| T01 | BLOCKED | — | `infra/docker-compose.yml` passes offline YAML/structural validation (parses, services `postgres`+`redis` present, named volumes present, both ports bound to `127.0.0.1` only); `scripts/check-infra.sh` passes `bash -n` syntax check; `make infra-up`/`infra-down`/`infra-logs`/`infra-reset` all fail with the clear "docker is not installed, see Gate H0" message rather than silently passing; `infra-reset` requires typing `reset` to confirm before `down -v` runs. **Not verified:** `docker compose config` (needs the `docker` CLI), `make infra-up` actually starting healthy containers, a real Postgres connection, a real Redis `PING`, or volume survival across a restart — Docker is not installed in this environment. | **Gate H0**: Docker is not installed/on PATH here. Needs the repo owner to install Docker Desktop and either run `make infra-up` themselves or hand this session a working `docker` CLI to re-run the full acceptance pass. |
| T02–T36 | NOT STARTED | — | — | Follow sequential backlog |

Do not replace NOT STARTED with DONE until acceptance tests actually passed. For partially checked tickets use IN PROGRESS/BLOCKED and include the missing test and manual gate.
