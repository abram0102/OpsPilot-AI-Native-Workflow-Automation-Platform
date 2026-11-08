# services/api

Spring Boot 4.1.2 / Java 21 backend. **T02** laid the skeleton. **T06** added the core schema and tenant-scoped repositories. **T07** added real OIDC JWT validation (signature/issuer/audience/expiry) and the server-side authorization policy — every path except the two health probes now requires a valid JWT, and every organization-scoped action goes through `OrganizationAccessService`, never a client-supplied role. **T08** added the first real endpoint, `GET /api/v1/me`, which also provisions a brand-new user's row on their first trusted login. **T09** added organization creation, membership listing, role management (with last-owner/owner-transfer guards) and the invitation flow (single-use hashed token, no email delivery in V1 — see `docs/DECISIONS.md` ADR-023–ADR-027). Tickets start at **T10**.

## Running locally

```bash
cp ../../.env.example ../../.env   # if you haven't already, from the repo root
make -C ../.. infra-up             # Postgres+pgvector and Redis — see infra/ (T01)
./mvnw spring-boot:run              # Flyway migrates on startup
```

The app binds to port 8080 (`API_PORT`). `local` profile defaults `app.security.allow-test-issuer=true`, so a token signed with the app's own self-generated test key pair (see `TestIssuerConfig`) is accepted; a real Auth0 token is not usable until T08 configures `API_OIDC_ISSUER_URI` for real.

## Seeding fictional demo data

```bash
make -C ../.. seed-demo
```

Creates two fictional tenants ("Acme Demo" / "Globex Demo"), each with one owner, one customer and a couple of tickets — useful for manually exercising two-tenant isolation before T28's automated security regression suite exists. Idempotent (skips an org that already exists); runs under a dedicated `seed` Spring profile that exits once done, rather than staying up as a second server instance.

## Structure

- `com.opspilot.common.web` — `RequestIdFilter`, `RequestIdSupport`, `ApiErrorResponse`, `GlobalExceptionHandler` (the stable error envelope from `docs/API_CONTRACT.md`).
- `com.opspilot.common.security` — `SecurityConfig` (JWT required everywhere but health), `JwtDecoderConfig` (real + test-issuer decoders), `TestIssuerConfig`, `ProductionSafetyGuard`, `AudienceValidator`, `JsonAuthenticationEntryPoint`/`JsonAccessDeniedHandler` (401/403 on the standard error envelope).
- `com.opspilot.common.seed` — `DemoDataSeeder` (`seed` profile only).
- `com.opspilot.auth` — `User`, `AuthenticatedPrincipal`, `OrganizationAccessService` (+ impl), `OrganizationAction` (the role/action permission matrix), `PrincipalResolvingJwtAuthenticationConverter`, `PrincipalAuthenticationToken`, `UserProvisioningService` (+ impl — first-login provisioning, docs/DECISIONS.md ADR-020), `MeController` (`GET /api/v1/me`).
- `com.opspilot.organization` — `Organization`, `Membership` (+ `MembershipRole`), `Invitation`, their repositories; `OrganizationService`/`MembershipService`/`InvitationService` (+ impls — creation, membership listing/role updates, invitation issuance/acceptance); `OrganizationController`/`InvitationController`; `InvitationTokens` (single-use hashed token generation).
- `com.opspilot.ticket` — `Customer`, `Ticket` (+ `TicketStatus`/`TicketPriority`), `TicketMessage` (+ `TicketMessageAuthorType`), `TicketTag`.
- Every tenant-scoped repository method takes an `organizationId` explicitly (e.g. `findByIdAndOrganizationId`) — there is no bare `findById` path to another tenant's row sitting around waiting to be misused.
- `knowledge`, `run`, `policy`, `internal`, `dispatch` packages are created by the tickets that need them (T13, T17, T21, T24), not speculatively ahead of time.

## Schema

`src/main/resources/db/migration/` — Flyway-owned, never hand-edited after the fact (AGENTS.md rule 4). `V001` is extensions/users/orgs/memberships/invitations; `V002` is customers/tickets/messages/tags, including the composite foreign keys (`tickets.(customer_id, organization_id)` → `customers.(id, organization_id)`, and similarly for ticket_messages/ticket_tags → tickets) that make the database itself reject a ticket referencing another organization's customer — not just application code (docs/DATA_MODEL.md section B.2, docs/DECISIONS.md ADR-013).

## Authorization

Every organization-scoped action is declared once in `OrganizationAction` with its minimum required `MembershipRole` (VIEWER < MEMBER < ADMIN < OWNER), and `OrganizationAccessService.requireAction(principal, orgId, action)` is the only place that matrix is enforced — it always re-reads the caller's role from the `memberships` table, never from anything the client sent (AGENTS.md rule 11). See `docs/DECISIONS.md` ADR-017 for how the real vs. test-only JWT decoders are composed.

## Tests

```bash
./mvnw test                                   # full suite — needs Docker (Testcontainers Postgres)
./mvnw test -Dtest='!ApiApplicationTests,!FlywayMigrationIntegrationTest,!MembershipRepositoryIntegrationTest,!TicketRepositoryIntegrationTest,!SecurityConfigIntegrationTest,!JwtAuthenticationIntegrationTest,!OrganizationAccessHttpIntegrationTest,!MeControllerIntegrationTest,!OrganizationHttpIntegrationTest,!InvitationHttpIntegrationTest'   # everything else, no Docker required
```

10 of the test classes boot a real Postgres via Testcontainers and therefore need Docker (as of T07, `SecurityConfig` itself depends on `UserRepository`, so even pure JWT-rejection tests need a real datasource) — see `docs/PROGRESS.md` for current local-environment status. GitHub Actions CI (`.github/workflows/ci.yml`) runs the full suite, Docker included.
