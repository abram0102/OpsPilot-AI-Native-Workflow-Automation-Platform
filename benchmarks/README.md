# benchmarks

k6 load profiles, the fixture they run against, and dated reports (T34).

Reports record measured results only. Where a figure is inferred rather than measured — such as
why an untouched endpoint got faster — the report says so.

| Path | What it is |
|---|---|
| `fixture.sql` | Org A 10,000 tickets, Org B 1,000 (`docs/SECURITY_AND_TESTING.md` §6's two tenant sizes). Fictional, idempotent |
| `k6/api-load.js` | The load profile: list, deep page, create, run status |
| `results/` | Raw k6 JSON summaries and console output, gitignored |
| `2026-11-13-report.md` | The first before/after report |

## Running it

```bash
createdb opspilot_bench && psql -d opspilot_bench -c 'CREATE EXTENSION vector'
cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local,seed   # migrate once
make bench
```

`make bench` loads the fixture, starts its own API with a throwaway test-issuer key, mints its own
token, runs k6, and tears everything down. It needs PostgreSQL and k6; no Redis, no worker, no
Docker, and no model.

`BENCH_LABEL=after make bench` labels the artefacts, which is how a before/after pair is produced.

## Two things worth knowing before you read a result

**The fixture is reloaded on every run, deliberately.** The `create` scenario inserts tickets —
about 25,000 of them in a 60-second baseline — so a second run against the leftovers would be
measuring the leftovers. `fixture.sql` deletes and recreates its two tenants so every run starts
from the same 10,000.

**The load generator shares a machine with the API and the database.** These numbers compare the
system against itself across a change. They are not a capacity estimate, and §6's "p95 under
500 ms" belongs on staging, not here.
