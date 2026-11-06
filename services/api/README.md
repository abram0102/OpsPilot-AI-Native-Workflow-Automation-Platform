# services/api

Spring Boot 4.1.2 / Java 21 backend. **T02** laid the skeleton (error envelope, request-ID propagation, a locked-down security posture). **T06** added the core schema (Flyway `V001`/`V002`), JPA entities and tenant-scoped repositories for users, organizations, memberships, invitations, customers, tickets, messages and tags. No REST endpoints exist yet — those start at **T07** (auth) and **T10** (tickets API).

## Running locally

```bash
cp ../../.env.example ../../.env   # if you haven't already, from the repo root
make -C ../.. infra-up             # Postgres+pgvector and Redis — see infra/ (T01)
./mvnw spring-boot:run              # Flyway migrates on startup
```

The app binds to port 8080 (`API_PORT`) and exposes only `/actuator/health/liveness` and `/actuator/health/readiness` without authentication; everything else is denied until ticket T07 adds real OIDC JWT validation and role-based authorization.

## Seeding fictional demo data

```bash
make -C ../.. seed-demo
```

Creates two fictional tenants ("Acme Demo" / "Globex Demo"), each with one owner, one customer and a couple of tickets — useful for manually exercising two-tenant isolation before T28's automated security regression suite exists. Idempotent (skips an org that already exists); runs under a dedicated `seed` Spring profile that exits once done, rather than staying up as a second server instance.

## Structure

- `com.opspilot.common.web` — `RequestIdFilter`, `RequestIdSupport`, `ApiErrorResponse`, `GlobalExceptionHandler` (the stable error envelope from `docs/API_CONTRACT.md`).
- `com.opspilot.common.security` — `SecurityConfig` (health-only allowlist; replaced by real auth in T07).
- `com.opspilot.common.seed` — `DemoDataSeeder` (`seed` profile only).
- `com.opspilot.auth` — `User` entity + repository.
- `com.opspilot.organization` — `Organization`, `Membership` (+ `MembershipRole`), `Invitation`.
- `com.opspilot.ticket` — `Customer`, `Ticket` (+ `TicketStatus`/`TicketPriority`), `TicketMessage` (+ `TicketMessageAuthorType`), `TicketTag`.
- Every tenant-scoped repository method takes an `organizationId` explicitly (e.g. `findByIdAndOrganizationId`) — there is no bare `findById` path to another tenant's row sitting around waiting to be misused.
- `knowledge`, `run`, `policy`, `internal`, `dispatch` packages are created by the tickets that need them (T13, T17, T21, T24), not speculatively ahead of time.

## Schema

`src/main/resources/db/migration/` — Flyway-owned, never hand-edited after the fact (AGENTS.md rule 4). `V001` is extensions/users/orgs/memberships/invitations; `V002` is customers/tickets/messages/tags, including the composite foreign keys (`tickets.(customer_id, organization_id)` → `customers.(id, organization_id)`, and similarly for ticket_messages/ticket_tags → tickets) that make the database itself reject a ticket referencing another organization's customer — not just application code (docs/DATA_MODEL.md section B.2, docs/DECISIONS.md ADR-013).

## Tests

```bash
./mvnw test                                   # full suite — needs Docker (Testcontainers Postgres)
./mvnw test -Dtest='!ApiApplicationTests,!FlywayMigrationIntegrationTest,!MembershipRepositoryIntegrationTest,!TicketRepositoryIntegrationTest'   # everything else, no Docker required
```

4 of the test classes boot a real Postgres via Testcontainers and therefore need Docker — see `docs/PROGRESS.md` for current local-environment status. GitHub Actions CI (`.github/workflows/ci.yml`) runs the full suite, Docker included.
