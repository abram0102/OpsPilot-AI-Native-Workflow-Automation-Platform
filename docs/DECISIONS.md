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

## ADR-008 — Spring Boot 4.1.2, and its restructured autoconfiguration packages

Status: **Accepted**, 2026-11-05. `docs/DEPENDENCY_DECISIONS.md`'s baseline target was "Spring Boot stable compatible with Java 21" without a pinned version; verifying against Spring Initializr's live metadata (AGENTS.md rule 4) at T02 time found Spring Boot 4.1.2 as the current stable GA release (4.2.0 is still milestone/RC only). This is a major-version jump past what either the blueprint author or this assistant had prior knowledge of, and it moved many autoconfiguration classes out of `org.springframework.boot.autoconfigure.*` into per-module packages, e.g.:

- `org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration` → `org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration`
- `org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration` → `org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration`
- `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration` → `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`
- `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest`/`AutoConfigureMockMvc` → `org.springframework.boot.webmvc.test.autoconfigure.*`
- `org.springframework.boot.test.web.client.TestRestTemplate` → `org.springframework.boot.resttestclient.TestRestTemplate` (and needs an explicit `@AutoConfigureTestRestTemplate`, plus a `spring-boot-restclient` dependency this project doesn't currently have — see `docs/DEPENDENCY_DECISIONS.md` T02 notes; T02's one security integration test uses MockMvc instead, which needed no extra dependency).

**Action for future tickets:** do not copy Spring Boot 2.x/3.x class names, annotation package paths, or Stack Overflow snippets from memory — verify the actual class in the resolved `~/.m2` jar (or current official docs) before using it, exactly as AGENTS.md rule 4 already requires for dependency *versions*. This caught real compile/runtime errors during T02 and will keep doing so for T06+.

## ADR-009 — Next.js 16 Cache Components, and verifying against its own bundled docs

Status: **Accepted**, 2026-11-05. `create-next-app@16.4.5` (the current stable, verified against npm's registry, not assumed from memory) scaffolds new apps with `cacheComponents: true` and `partialPrefetching: true` by default — a materially different rendering model (Partial Prerendering, `"use cache"`, the `instant` route segment config) from pre-16 Next.js. `next dev` itself auto-generates `apps/web/AGENTS.md` on every run, explicitly warning that "this version has breaking changes" and pointing at `node_modules/next/dist/docs/` as the authoritative reference — not a training-data assumption. That generated file is committed on purpose (it's idempotent and harmless) rather than fought.

This was not theoretical: the dev overlay's "Instant navigation" validator flagged both `app/[orgId]/dashboard/page.tsx` and `app/[orgId]/layout.tsx` for reading the dynamic `orgId` param outside a `<Suspense>` boundary. Fixed by (a) deferring the `params` read into a child `<Suspense>`-wrapped component in the dashboard page, matching the documented "maximizing the static shell" pattern, and (b) setting `export const instant = false` on the org layout, since its nav/toolbar genuinely depend on `orgId` end to end and will be rebuilt around real session data in T08/T09 anyway — an honest acknowledgment rather than a forced, premature optimization.

**Action for future tickets (T08–T12, T22, T27):** before writing data-fetching, auth-cookie, or polling code in `apps/web`, read the relevant guide under `node_modules/next/dist/docs/01-app/`, the same way ADR-008 requires verifying Spring Boot 4 classes against the actual resolved jars.

## ADR-010 — Node 26 (current active LTS) for `apps/web`, not whatever `nvm`'s local alias cache said

Status: **Accepted**, 2026-11-05. The dev machine's active Node via `nvm` was v20.20.2, which satisfies Next.js 16's stated `engines.node: >=20.9.0`. However, `pnpm test` crashed under it: a transitively-pulled `undici@8.x` (via Vite 8/Vitest 5) throws `TypeError: Cannot read properties of undefined (reading 'bind')` in its decompression interceptor, incompatible with the `undici@6.24.1` Node 20.20.2 bundles internally. Installed the actual current active LTS via `nvm install --lts` (resolved to v26.13.0 — `nvm`'s own local alias metadata for "lts/krypton" was stale/out of date) and pinned it in `apps/web/.nvmrc`. Did **not** change the machine's global `nvm alias default` (still 20) — that's the user's own environment choice outside this project's scope; every command in this ticket explicitly ran `nvm use` first. Record the exact Node version actually used in CI once T05 is implemented.

## ADR-011 — CI: GitHub Actions pinned by commit SHA; Dependabot over Renovate; gitleaks as a personal-account repo

Status: **Accepted**, 2026-11-05. Every third-party action in `.github/workflows/ci.yml` is pinned to an exact commit SHA (with the human-readable version as a trailing comment), not a floating tag — `astral-sh/setup-uv`'s own README recommends this, and it matches `docs/SECURITY_AND_TESTING.md`'s supply-chain mitigation ("protected main branch" etc.): a tag can be force-moved to point at different code; a commit SHA cannot. SHAs were resolved from each action's own tag refs (`git ls-remote`), not guessed.

Dependabot (native, config-file-only) was chosen over Renovate because Renovate requires installing a separate GitHub App and authorizing it on the repository — a human/account action outside what a config file alone can set up — while Dependabot needs nothing beyond `.github/dependabot.yml`. `package-ecosystem: uv` is used for `services/agent` (confirmed as a distinct, supported ecosystem from `pip`, not a guess).

`gitleaks/gitleaks-action@v3` requires a paid license (`GITLEAKS_LICENSE` secret) only for repositories owned by a GitHub **organization**; personal-account repos (this one, under `abram0102`) need no license. If this repository is ever transferred to an organization, add that secret or switch to running the open-source `gitleaks` CLI directly.

## ADR-012 — Spotless (google-java-format) added to `services/api` in T05, not T02

Status: **Accepted**, 2026-11-05. `docs/SECURITY_AND_TESTING.md` section 8 (CI acceptance gates) lists "Java compile/lint" as a required PR gate, but T02's ticket text didn't call out a specific linter/formatter — T05 ("Baseline CI and repo quality automation") is the ticket that actually wires up CI gates, so the formatter was added here instead of retrofitted into T02's commit. `spotless-maven-plugin` + `googleJavaFormat` was chosen as the most common, low-configuration choice for a new Spring Boot project; `./mvnw spotless:apply` reformats, `./mvnw spotless:check` (wired into `make lint` and CI) fails the build on drift.

## ADR-013 — Plain UUID foreign-key columns in JPA entities, not `@ManyToOne`; composite FKs enforce tenant isolation at the DB level

Status: **Accepted**, 2026-11-05. T06's entities (`Ticket.customerId`, `TicketMessage.ticketId`, `Membership.organizationId`/`userId`, etc.) store foreign keys as plain `UUID` fields rather than Hibernate-managed `@ManyToOne` associations. This keeps every tenant-scoped query explicit (`findByIdAndOrganizationId(...)`, never a bare `findById`) and avoids lazy-loading/N+1 surprises across what will become a large entity graph. It also sidesteps a genuine Hibernate mapping difficulty: the DB's composite foreign keys (e.g. `tickets.(customer_id, organization_id)` → `customers.(id, organization_id)`, added specifically so the database itself rejects a ticket referencing another organization's customer — docs/DATA_MODEL.md section B.2's literal example) don't map cleanly onto a `@ManyToOne` when one of the two FK columns (`organization_id`) is already its own mapped field on the owning entity.

Verified with real tests, not just asserted: `TicketRepositoryIntegrationTest.ticketCannotReferenceACustomerFromAnotherOrganization` inserts a cross-tenant ticket and asserts the DB itself (`DataIntegrityViolationException`), not application code, rejects it.

## ADR-014 — Demo-data seeding is a separate Spring profile/process, not an app-startup side effect

Status: **Accepted**, 2026-11-05. `DemoDataSeeder` (`@Profile("seed")`) only runs when `make seed-demo` activates the `seed` profile alongside `local`; `application-seed.yml` sets `spring.main.web-application-type: none` so the process seeds and exits rather than staying up as a second, confusing instance of the API. This matches `docs/DEPLOYMENT_RUNBOOK.md`'s "`make seed-demo` ... deterministic seed idempotent and local/test only" and keeps seeding out of the normal `api-dev` startup path entirely — nobody is surprised by demo orgs appearing just from running the app normally.

## ADR-015 — Testcontainers' Postgres image must be `pgvector/pgvector:pg16`, not plain `postgres:latest`

Status: **Accepted**, 2026-11-05. T02's Spring Initializr scaffold generated `TestcontainersConfiguration` pointing at plain `postgres:latest`. That went unnoticed through T02–T05 because no Flyway migration existed yet for it to matter. T06's first real CI run (confirmed via the actual GitHub Actions log, not guessed — see below) failed all Docker-backed tests with `ERROR: extension "vector" is not available`: `V001__extensions_and_core.sql`'s `CREATE EXTENSION vector` has no shared library to load against stock Postgres. Fixed by pointing `TestcontainersConfiguration` at `pgvector/pgvector:pg16` (the same image `infra/docker-compose.yml` already uses, per ADR-007) with `DockerImageName.asCompatibleSubstituteFor("postgres")`, which Testcontainers requires for any Postgres-based image not literally named `postgres`.

**How this was actually diagnosed:** GitHub now requires sign-in to view Action run logs even on a public repository, and the REST API's log-download endpoint requires admin rights — neither was available in this session. The operator declined to grant `gh auth login` org-wide OAuth scopes (a reasonable call for a one-off log read), so they instead downloaded the log archive from the GitHub web UI themselves and shared the relevant file directly. The root cause was then found by grep, not guesswork. Record this path for future CI failures in this environment: ask the operator for the downloaded log archive rather than assuming log/API access.

## ADR-016 — Jackson 3 (`tools.jackson.*`), not `com.fasterxml.jackson.*`, in this Spring Boot 4 stack

Status: **Accepted**, 2026-11-05. Needed `ObjectMapper` for T07's JSON-writing security error handlers and found `com.fasterxml.jackson.databind` simply isn't on this project's classpath — Spring Boot 4.1.2 pulls in `spring-boot-starter-jackson` → `tools.jackson.core:jackson-databind:3.1.7`, Jackson's real "Jackson 3" release, which moved `databind`/`core` to a new Maven groupId (`tools.jackson.core`) **and** a new package namespace (`tools.jackson.databind.ObjectMapper`, `tools.jackson.core.JacksonException`, etc.) — `jackson-annotations` alone stays at the old `com.fasterxml.jackson.core` coordinates. `JacksonException` is now an unchecked `RuntimeException` (previously checked `IOException`-derived). Verified by inspecting the resolved jar's actual class list rather than assuming Jackson 2's API, the same discipline ADR-008 established for Spring Boot 4's autoconfiguration packages.

**Action for future tickets:** anything using Jackson directly (custom serializers, `@JsonProperty` on non-entity DTOs, etc.) must import from `tools.jackson.*`, not `com.fasterxml.jackson.databind`/`com.fasterxml.jackson.core`.

## ADR-017 — T07 security architecture: lazy real decoder + conditional test-issuer decoder, composite with test-first fallback

Status: **Accepted**, 2026-11-05. Implements docs/TASKS.md T07's "signed-token test infrastructure" as two independently-built `JwtDecoder`s rather than one:

- The **real** decoder (`JwtDecoderConfig.issuerJwtDecoder`), built from `app.security.oidc-issuer-uri` via `JwtDecoders.fromIssuerLocation(...)`, marked `@Lazy` so its (otherwise eager) OIDC-discovery HTTP call never runs at application startup — critical because `API_OIDC_ISSUER_URI` is a placeholder until T08, and eager resolution would break every environment without live Auth0 configured, local dev and CI included. Deliberately **not** using Spring Boot's own auto-configured resource-server `JwtDecoder` (triggered by the standard `spring.security.oauth2.resourceserver.jwt.issuer-uri` property) for the same reason — that auto-configuration is not lazy. This project's own `app.security.*` properties are used instead, specifically to keep decoder construction lazy.
- The **test-only** decoder, built only when `app.security.allow-test-issuer=true` (default `true` under the `local` profile, hard-refused under `production` by `ProductionSafetyGuard` — the literal T07 acceptance check "production profile refuses test-only issuer"), validated against an RSA key pair this same process generates fresh at startup (`TestIssuerConfig`) — no key material of any kind lives in source control.

The two are combined by `TestFirstDelegatingJwtDecoder`: try the test decoder first, fall back to the real one only on failure. This is deliberately **not** routed by peeking the token's own unverified `iss` claim — that would let a test-signed token which fraudulently claims some other issuer skip the test decoder entirely, masking the actually meaningful rejection (wrong issuer/audience/expired) behind the real decoder's unrelated "can't reach placeholder issuer" failure. Trying the test decoder first means its signature check runs regardless of claimed issuer, so every rejection test (`JwtAuthenticationIntegrationTest`) gets the semantically correct failure reason.

**Consequence discovered while implementing, not predicted up front:** wiring `UserRepository` into `SecurityConfig` (so `PrincipalResolvingJwtAuthenticationConverter` can resolve the caller) means the security filter chain itself now depends on a real datasource — so even "no bearer token -> 401"-style tests can no longer exclude DataSource/JPA/Flyway autoconfiguration the way T02's original `SecurityConfigIntegrationTest` did. That test was updated (now Testcontainers-backed, and its "every other path" assertions changed from `isForbidden()` to `isUnauthorized()` since `anyRequest().authenticated()` replaced `anyRequest().denyAll()`) rather than left broken or deleted.

## ADR-018 — `@Lazy` on a `@Bean` method does nothing unless the injection point also defers resolution

Status: **Accepted**, 2026-11-06. T07's first real CI run (confirmed via a manually downloaded log archive, same pattern as ADR-015) failed every Docker-backed security test: `UnsatisfiedDependencyException` building `jwtDecoder`, caused by `issuerJwtDecoder` throwing `Unable to resolve the Configuration with the provided Issuer of "https://REPLACE-ME.auth0.com/"` — the exact eager OIDC-discovery call ADR-017's `@Lazy` annotation was supposed to prevent.

**Root cause:** `@Lazy` on a `@Bean` method only defers that bean's construction *until something asks for it* — it does not make an injection point itself lazy. `jwtDecoder(JwtDecoder issuerJwtDecoder, ...)` declared `issuerJwtDecoder` as a plain method parameter, so Spring had to fully resolve it (triggering the real decoder's construction and its live network call) just to build `jwtDecoder` itself, regardless of `@Lazy` on the producing bean. This only surfaces when something actually depends on the lazy bean directly — which is exactly why it passed local compilation and Docker-independent tests and only broke under the Docker-backed tests that exercise `jwtDecoder`.

**Fix:** changed the `jwtDecoder` factory method's parameter to `ObjectProvider<JwtDecoder> issuerJwtDecoder`, and `TestFirstDelegatingJwtDecoder` now holds that `ObjectProvider` rather than a resolved `JwtDecoder`, calling `.getObject()` only inside the fallback branch of `decode(...)` — so the real decoder, and its OIDC discovery call, is now actually never constructed unless a token falls through the test decoder. The `allow-test-issuer=false` path (`issuerJwtDecoder.getObject()`) still resolves it directly, which is correct there: in that mode it's the only decoder, so failing fast on an unreachable issuer is the intended behavior.

**Action for future tickets:** any bean that depends on a `@Lazy`-marked `@Bean` must inject it via `ObjectProvider<T>` (or `@Lazy` on the injection point itself), never as a plain constructor/method parameter — otherwise `@Lazy` on the producing side is silently ineffective.

## ADR-019 — `@auth0/nextjs-auth0` v4: renamed env vars, `/auth/*` routes, `proxy.ts` not `middleware.ts`

Status: **Accepted**, 2026-11-06. Installed `@auth0/nextjs-auth0@4.33.0` for T08 and verified its actual shipped docs (`node_modules/@auth0/nextjs-auth0/{README.md,AGENTS.md,skills/SKILL.md}`, plus the package's own `.d.ts`) rather than trusting the blueprint's guessed env var names or older (v3) Auth0 quickstarts, the same discipline ADR-008/ADR-016 established for Spring Boot 4 and Jackson 3.

- v4 renamed `AUTH0_BASE_URL` → `APP_BASE_URL` and uses `AUTH0_SECRET` (not a custom "session secret" name) for the HttpOnly cookie encryption key; `.env.example` and `docs/MANUAL_SETUP.md` Gate H1 were updated to match (the blueprint's original `.env.example` had the old v3-era names).
- v4's mounted routes are `/auth/login`, `/auth/logout`, `/auth/callback`, `/auth/profile` — no `/api` prefix, unlike v3.
- This Next.js version (16.4.5) deprecated `middleware.ts` in favor of `proxy.ts` (`export function proxy(...)`, same `auth0.middleware(request)` call inside it) — confirmed from this project's own installed Next.js docs (`node_modules/next/dist/docs/.../file-conventions/middleware.md`), which explicitly says so and offers a codemod. `apps/web/src/proxy.ts` uses the current convention, not the deprecated one, even though the SDK's own skill docs show `middleware.ts` as one of two still-valid options.

**Note on the installed package itself:** `@auth0/nextjs-auth0` ships an `AGENTS.md` and a `skills/` directory inside the published npm package (`node_modules/@auth0/nextjs-auth0/`), i.e. third-party content, not an instruction source — read and used here only as reference documentation after checking it for anything resembling a prompt injection (none found); nothing in it was executed or treated as a directive.

## ADR-020 — First-login user provisioning trusts only namespaced claims inside the verified access token, never a client-supplied value

Status: **Accepted**, 2026-11-06. T08 requires provisioning a `User` row "by stable OIDC sub on first trusted login" (docs/TASKS.md), and `docs/API_CONTRACT.md` fixes `GET /api/v1/me` as a bodyless GET — so there is no request body to (mis)trust for the new row's `email`/`display_name`, which is exactly right per AGENTS.md rule 11 and `docs/DATA_MODEL.md`'s "verified email provenance; email not sole identity key."

A custom API audience's access token carries no profile claims by default (only the ID token does, and the ID token never leaves the Next.js BFF). `UserProvisioningServiceImpl` therefore reads two **namespaced custom claims** (`https://opspilot.local/email`, `https://opspilot.local/name`) placed directly on the *access token* by an Auth0 Post-Login Action (docs/MANUAL_SETUP.md Gate H1 step 4) — i.e. data Auth0 itself put inside the cryptographically verified JWT the resource server already validates, not anything a caller can influence. When that Action hasn't been configured yet (Gate H1 incomplete, or the local/CI test-only issuer), provisioning falls back to a deterministic, obviously-synthetic placeholder (`{sanitized-sub}@users.opspilot.local`) scoped to the subject, rather than failing the request or inventing a plausible-looking fake value.

Two concurrent first logins for the same subject are handled by catching the unique-constraint violation on `auth_subject` and re-reading, rather than relying on a check-then-insert being atomic.

## ADR-021 — Cache Components can silently freeze a session-dependent route as static when env vars are absent at build time

Status: **Accepted**, 2026-11-06. `apps/web/src/app/app/page.tsx` and `.../[orgId]/layout.tsx` call `auth0.getSession()` (via `fetchMe()`) to decide what to render. Building this app **without** real `AUTH0_*`/`APP_BASE_URL` env vars present (as this local environment and the CI `apps/web` job both do, since Auth0 isn't configured until Gate H1) makes `auth0` resolve to `null` at module load — so the session-reading code path, and the `cookies()` call inside it, is never actually executed during Next's static analysis of the route. Cache Components then has no dynamic dependency to detect and happily prerenders `/app` and `/app/[orgId]/*` as fully static (`○`) instead of partially dynamic (`◐`).

Rebuilding with real (even dummy, locally-generated) Auth0 env vars present reproduces the correct, dynamic classification and confirms `export const instant = false` (already present on both routes per the "Migrating an existing app" section of `node_modules/next/dist/docs/.../guides/authentication-with-cache-components.md`) is sufficient to avoid a build error from reading the session outside a `<Suspense>` boundary.

**Consequence:** any build of `apps/web` that is meant to be deployed must have real Auth0 env vars present *at `next build` time*, not just at runtime — a CI/build step that only smoke-tests the build without secrets (as this repo's own `.github/workflows/ci.yml` does, deliberately, since CI must not need real secrets per AGENTS.md rule 16) will produce a build that looks successful but would serve a stale, pre-session-check static page if ever deployed as-is. This must be carried forward into whichever deployment ticket (T31+) actually builds the production image: that build step needs real secrets, not CI's. Noted here so it isn't rediscovered as a production incident.

## ADR-022 — Node 26 no longer bundles Corepack; `pnpm` needs a one-time `npm install -g corepack`

Status: **Accepted**, 2026-11-06. T04/T05 assumed `nvm use && corepack enable && corepack prepare pnpm@... --activate` (recorded in `docs/DEPENDENCY_DECISIONS.md`) would always make `pnpm` available after switching to the pinned Node version (`apps/web/.nvmrc`, currently Node 26.13.0). On a fresh terminal in this same environment, `corepack` itself was missing (`zsh: command not found: corepack`) — Node stopped bundling Corepack by default starting with Node 25, so a Node 26 install via `nvm` has no `corepack` binary until one is installed separately (`npm install -g corepack@latest`, a one-time per-Node-version step; each `nvm`-managed Node version has its own global `node_modules`). The Makefile's `NVM_USE` helper only runs `nvm use`, not this; it was not changed, since this is a one-time machine-setup gap (akin to Gate H0), not something every `make` invocation should try to silently repair.

**Action for future tickets:** if a fresh clone or CI image ever reports `corepack: command not found` after `nvm use`, run `npm install -g corepack@latest` once for that Node version before `corepack enable`.

Further decisions and deviations must be recorded here with date, context, alternatives, rationale and consequences.
