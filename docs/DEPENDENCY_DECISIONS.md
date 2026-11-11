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

## T02 notes — 2026-11-05

- **Spring Boot 4.1.2** (parent `spring-boot-starter-parent:4.1.2` — note: no `.RELEASE` suffix; that qualifier appears only in Spring Initializr's metadata `id` field, not the actual Maven Central coordinate). Verified against `https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml`; 4.2.0 is milestone/RC-only as of this date, so 4.1.2 is current stable. Transitively: Spring Framework 7.0.10, Flyway core 12.4.0, HikariCP 7.0.2, PostgreSQL JDBC driver 42.7.14.
- **Java 21** (`<java.version>21</java.version>` in `services/api/pom.xml`, matching the blueprint baseline). The JDK actually installed on this dev machine is OpenJDK 24; Maven compiles with `--release 21` via the Spring Boot parent's compiler plugin config, so bytecode/API surface is Java 21, while the build tool itself runs on 24. This is expected to work on any JDK >= 21 that Spring Boot 4 supports; record the CI runner's exact JDK once T05 is implemented.
- **Apache Maven 3.10.0**, fetched automatically via the generated Maven Wrapper (`.mvn/wrapper/maven-wrapper.properties`, `distributionUrl` pointing at the official `repo.maven.apache.org` artifact) — no system-wide Maven was installed; `./mvnw` is the only supported entry point, per `docs/TASKS.md` T02.
- Project scaffolded from the official Spring Initializr (`https://start.spring.io`) with dependencies `web, validation, security, oauth2-resource-server, data-jpa, postgresql, flyway, actuator, testcontainers`, then hand-edited (removed empty `<url>`/`<licenses>`/`<developers>`/`<scm>` placeholder blocks).
- **Spring Boot 4 restructured many autoconfiguration/test-support classes into per-module packages** (see `docs/DECISIONS.md` ADR-008) — e.g. `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`, `org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration`, `org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration`, `org.springframework.boot.webmvc.test.autoconfigure.{WebMvcTest,AutoConfigureMockMvc}`. `org.springframework.boot.resttestclient.TestRestTemplate` additionally requires a `spring-boot-restclient`/`RestTemplateBuilder` dependency this project doesn't currently declare; T02's security test uses MockMvc instead, which needs no such extra dependency. Revisit if a future ticket specifically wants `TestRestTemplate` over real HTTP.

## T25 notes — 2026-11-11

- **No new Python dependency.** `redis==8.1.0` has been in `services/agent/uv.lock` since T03 and ships the async client (`redis.asyncio`) this consumer uses; `XAUTOCLAIM` and `xpending_range` are both in it, so nothing had to be added. Confirmed against the locked version rather than assumed.
- **No fake-Redis test double**, deliberately. `fakeredis` exists and would have let these tests run anywhere, but the things under test here *are* `XAUTOCLAIM`'s idle accounting, the pending-entries list and `times_delivered`. A reimplementation of those would only prove that the reimplementation agrees with itself. The tests use a real server, gated behind the `redis` marker exactly as the `db` marker gates PostgreSQL.
- **CI gains a `redis:7-alpine` service container** in the `services/agent` job, matching `infra/docker-compose.yml` and the Java side's Testcontainers configuration, with `REDIS_TEST_URL` and a dedicated "Redis-backed tests (must actually run)" step. The fixture fails rather than skips when `CI` is set — verified by running it both ways: `CI=true` with no `REDIS_TEST_URL` exits **1** with 31 errors, and without `CI` it exits **0** with 31 skips, so a lost container fails the build instead of passing while proving nothing.
- **Local verification used Redis 8.10.3** (Homebrew, run as a throwaway instance on port 56379 with no persistence — not a background service), while CI and Compose use 7.x. Recorded because it is a real difference: every command used here (`XGROUP CREATE`, `XREADGROUP`, `XAUTOCLAIM`, `XPENDING`, `XACK`, `XINFO GROUPS`) has been stable since 6.2, and CI is what proves the 7.x behaviour. The local install was made with the operator's explicit approval, the same way PostgreSQL was for T23.

## T24 notes — 2026-11-11

- **`spring-boot-starter-data-redis` (4.1.2)** added to `services/api/pom.xml`, with **no version element** — it is managed by the Spring Boot BOM the project already imports, so it tracks the Boot version rather than drifting on its own. Resolved transitively: **`spring-data-redis 4.1.2`** and **`io.lettuce:lettuce-core 7.5.2.RELEASE`** (confirmed with `./mvnw dependency:tree`, not assumed). Lettuce is Boot's default client; Jedis was not added, because nothing here needs it and two clients on the classpath is an ambiguity, not a choice.
- **No Testcontainers Redis module.** There is no official one, and Spring Boot's `@ServiceConnection` recognises a plain `GenericContainer("redis:7-alpine")` by image name and wires `spring.data.redis.*` from it. Verified by running the publisher tests against it rather than inferred from the annotation's documentation. A third-party module would have been a dependency added for something the framework already does.
- **`redis:7-alpine`** in tests matches `infra/docker-compose.yml` exactly, so the test suite and local development run against the same server version.
- No new Python dependency: `redis==8.1.0` has been in `services/agent/uv.lock` since T03 and is what T25's consumer will use. Nothing in `services/agent` changed in this ticket beyond a docstring.

## T19 notes — 2026-11-10

- **`langgraph-checkpoint-postgres 3.2.0`** added as a direct runtime dependency of `services/agent` (pulling `psycopg-pool 3.3.3`). `langgraph-checkpoint 4.3.1` was already present transitively but provides only the base abstractions and an in-memory saver — T19 requires a *persistent* checkpointer so a paused run survives a worker restart, which is the whole basis of the approval pause.
- **These tables are deliberately not Flyway's.** ADR-007 says Flyway owns all schema, but `docs/DATA_MODEL.md` carves this out explicitly: LangGraph checkpoint tables "are initialized by the installed checkpointer package and use stable `thread_id=agent_runs.id`, not manually invented checkpoint columns." So the checkpointer's own `setup()` creates them and no migration describes them; what the doc asks for instead is that the ownership split be documented, which is what this entry and ADR-043 do.
- No other new dependencies — the graph, its state and the policy gate are standard library plus what T15/T16 already brought in.

## T16 notes — 2026-11-09

- **`pypdf 7.0.0`** added as a runtime dependency of `services/agent` — the first PDF reader in the repo (none existed). Chosen as a pure-Python, actively maintained, BSD-licensed parser that executes nothing from the document: no JavaScript, no embedded-file execution, no network fetch. Deliberately not PyMuPDF, whose AGPL licence would bind this project's own terms (and `docs/DECISIONS.md` ADR-006 leaves licensing open), and not a subprocess-based tool, which would add a process boundary to sandbox rather than remove one. `docs/SECURITY_AND_TESTING.md`'s "PDF sandbox/timeouts and decompression guards" requirement is met in `knowledge/extraction.py` rather than by the library: a page cap, a cap on total extracted characters checked *as pages are read* (so a decompression bomb trips it part-way instead of after expanding), encrypted PDFs refused outright, and every parser exception converted into a coded `ExtractionError`.
- **No `testcontainers` for Python.** The database-backed tests use a `pgvector/pgvector:pg16` **service container** on the CI agent job instead, reached via `POSTGRES_TEST_DSN`. A service container starts once per job, needs no Docker-in-test, and keeps the local story honest: without that variable the tests skip, which is the only possible behaviour under Gate H0. The fixture escalates that skip to a failure whenever `CI` is set, so a lost service container fails the build rather than passing silently.
- No other new dependencies — chunking, the object reader and the vector literal are all standard library plus the already-present `psycopg`.

## T15 notes — 2026-11-09

- **`httpx` promoted from a dev-only dependency to a runtime one** (`>=0.27`, locked at `0.28.1` — already in the graph since T03, so this adds no new package and `uv.lock` changes only by moving it into the project's runtime requirements). The real provider adapter speaks the OpenAI-style REST shape directly instead of going through a vendor SDK; see `docs/DECISIONS.md` ADR-039 for why.
- **No AI vendor SDK added.** `openai`/`anthropic` are deliberately *not* dependencies. `LLM_PROVIDER` is an environment knob and several hosted and self-hosted backends expose the same `/chat/completions` + `/embeddings` shape, so one thin adapter serves them all; adding an SDK would also pre-empt T20, which is the ticket gated on a real key (Gate H2). Revisit at T20 if live testing shows a provider needs SDK-specific behaviour.
- **No retry library added.** `tenacity 9.2.1` is present but only transitively (via `langchain-core`), so using it would mean declaring a direct dependency. The retry budget and circuit breaker here are ~60 lines with semantics specific to this project (retry only `AiProviderError.retryable`; the breaker counts *consecutive* failures across calls), so they are hand-rolled in `providers/resilience.py` with an injectable sleep for testing.
- **`gitleaks 8.30.1`** installed locally via Homebrew (same tool the CI secret-scan job runs), so the secret scan can be reproduced offline. Added because T15's tests necessarily contain strings *shaped* like API keys (`sk-test-only-not-a-real-key` and similar) and that needed verifying rather than assuming — `gitleaks git .` reports no leaks across all tracked history. Note that `gitleaks dir .` additionally flags 18 findings inside `apps/web/.next/`, which is build output and gitignored, so it is not part of what CI scans.
- **No tokenizer added** (`tiktoken` is not a dependency). Input budgeting therefore uses an explicitly-named character-length *estimate*, never presented as a token count. Real usage numbers come from a provider's own response and are T20's to record.

## T13 notes — 2026-11-09

- **AWS SDK v2 `2.57.11`** (`software.amazon.awssdk:bom`, imported in `<dependencyManagement>`, plus the `s3` artifact only). Verified before pinning: Maven Central's own `maven-metadata.xml` for the BOM lists `2.57.11` as its latest version (updated 2026-11-06, i.e. actively maintained). A first attempt also declared `software.amazon.awssdk:s3-presigner` as a separate dependency (based on recollection of older SDK layouts) — this failed to resolve (`'dependencies.dependency.version' ... is missing`, since the BOM doesn't manage an artifact that doesn't exist). Checked for real rather than guessing further: Maven Central's search API returns zero results for any `*presigner*` artifact in the `software.amazon.awssdk` group, and inspecting the actual downloaded `s3-2.57.11.jar` shows `S3Presigner` and all its presign-request/response model classes are bundled directly inside the `s3` artifact in the current SDK — so no second dependency is needed at all. See docs/DECISIONS.md ADR-036.
- No other new dependencies — `StorageService`'s local adapter is pure JDK (`java.nio.file`, `java.security.MessageDigest`).

## T11 notes — 2026-11-08

- **`openapi-typescript` 7.13.0** — generates `packages/contracts/src/schema.d.ts` from the committed `openapi.json`. Current stable per `npm view openapi-typescript version` at the time of writing. Invoked via pinned `npx` (both in `packages/contracts/package.json`'s `generate` script and its drift-check script) rather than installed as a tracked `devDependency` with its own lockfile — this package has no other build step, so a lockfile would be pure ceremony for one pinned tool invocation.
- **`openapi-fetch` 0.17.0** — the typed fetch wrapper `apps/web/src/lib/api/client.ts` wraps, added as a real `apps/web` dependency (it ships runtime code, unlike `openapi-typescript`). Current stable per `npm view openapi-fetch version`. Chosen over `openapi-generator-cli`'s TypeScript templates: zero codegen ceremony, tiny runtime, built specifically to pair with `openapi-typescript`'s output — see docs/DECISIONS.md ADR-033.
- No new Java dependency — `springdoc-openapi` (already added in T10, ADR-028) is what actually serves the spec this ticket generates from.

## T10 notes — 2026-11-08

- **`org.springdoc:springdoc-openapi-starter-webmvc-ui` 3.2.0**, added to `services/api/pom.xml` (new dependency — T06-T09 needed none). Verified before pinning: Maven Central's own `maven-metadata.xml` for this artifact lists `3.2.0` as both `<release>` and `<latest>`; the `v3.x` line is the one that tracks Spring Boot 4 / Spring Framework 7 (same artifact id as `v2.x`, which targets Spring Boot 3). See `docs/DECISIONS.md` ADR-028 for why (OpenAPI annotations only — generation/drift-check is T11) and the access-control consequence it flagged for T11.
- No other new dependencies this ticket — ticket/customer/message persistence (JPA entities, Flyway schema) already existed from T06.

## T08 notes — 2026-11-06

- **`@auth0/nextjs-auth0` 4.33.0** (`apps/web/package.json`) — verified current via `npm view @auth0/nextjs-auth0 version`; peer ranges (`next: ^16.0.10`, `react`/`react-dom: ^19.2.1`) satisfied by this project's Next 16.4.5/React 19.3.0. Env var names, mounted route paths (`/auth/*`, not `/api/auth/*`) and the `proxy.ts` vs `middleware.ts` choice were verified against the package's own shipped docs and this project's installed Next.js docs, not assumed — see `docs/DECISIONS.md` ADR-019.
- No new backend dependency for T08 — `UserProvisioningService` only uses what T07 already pulled in (Spring Security's `Jwt`).
- Local machine note, not a dependency change: this environment's Node 26.13.0 (via `nvm`) has no bundled Corepack, so `pnpm` wasn't on `PATH` until `npm install -g corepack@latest` was run once — see `docs/DECISIONS.md` ADR-022. GitHub Actions CI is unaffected (its `corepack enable` step already worked in every prior CI run).

## T07 notes — 2026-11-05

- **Jackson 3.1.7** (`tools.jackson.core:jackson-databind`), pulled in transitively via `spring-boot-starter-jackson` — not the `com.fasterxml.jackson.core:jackson-databind` 2.x most prior knowledge assumes. See `docs/DECISIONS.md` ADR-016.
- **Nimbus JOSE+JWT 10.9.1** (`com.nimbusds:nimbus-jose-jwt`) and **Spring Security OAuth2 JOSE/resource-server 7.1.2** (`org.springframework.security:spring-security-oauth2-jose` / `-resource-server`) — both transitively resolved via `spring-boot-starter-security-oauth2-resource-server` (already declared in T02's `pom.xml`); no new dependency added for T07. Verified actual class locations (`org.springframework.security.oauth2.jwt.*`, `com.nimbusds.jose.*`/`com.nimbusds.jwt.*`) against the resolved jars before use — these packages turned out to be unchanged from pre-Spring-Boot-4 versions, unlike Jackson and Spring Boot's own autoconfiguration classes.

## T05 notes — 2026-11-05

- GitHub Actions used in `.github/workflows/ci.yml`, each pinned by commit SHA (see `docs/DECISIONS.md` ADR-011): `actions/checkout@v7.0.1`, `actions/setup-java@v6.2.0`, `astral-sh/setup-uv@v10.3.3`, `actions/setup-node@v7.1.0`, `gitleaks/gitleaks-action@v3.0.0`. All resolved from each repo's actual tag refs via `git ls-remote`, not assumed from memory — `actions/setup-java` and `astral-sh/setup-uv` in particular are well past whatever major version a pre-trained guess would produce.
- `com.diffplug.spotless:spotless-maven-plugin:3.11.0` (current stable per `repo.maven.apache.org`'s metadata) added to `services/api/pom.xml`, configured with `googleJavaFormat` (default bundled version), `removeUnusedImports`, `importOrder`.
- `actionlint 1.7.12` and `shellcheck 0.11.0` installed via Homebrew to offline-validate the workflow YAML and `scripts/*.sh` before pushing — this caught and fixed a real shellcheck warning in `scripts/check-infra.sh` (a `# shellcheck disable` comment separated from its target line by a blank line, so it wasn't actually suppressing anything).
- Dependabot ecosystems: `maven` (`services/api`), `uv` (`services/agent` — confirmed as its own supported ecosystem distinct from `pip`), `npm` (`apps/web`), `docker` (`services/agent`, the only Dockerfile that exists yet), `github-actions` (root).

## T04 notes — 2026-11-05

- **Node 26.13.0** — the current active LTS (`nvm install --lts`), pinned in `apps/web/.nvmrc`. Node 20.20.2 (the machine's prior `nvm` default) satisfies Next.js 16's documented `>=20.9.0` floor but crashes `pnpm test` with an `undici` incompatibility — see `docs/DECISIONS.md` ADR-010. The machine's global `nvm` default was left untouched; always run `nvm use` in `apps/web` first.
- **pnpm 12.16.1**, activated via Corepack (`corepack enable && corepack prepare pnpm@12.16.1 --activate`) rather than a separate global install.
- **Next.js 16.4.5**, **React 19.3.0** / **React DOM 19.3.0**, **TypeScript 5.9.3** — scaffolded via the official `create-next-app@16.4.5` (verified as the current stable via `npm view next@latest version`, not assumed), App Router, `src/` layout, ESLint, no Tailwind (not requested by the blueprint; CSS Modules used instead).
- Test/format tooling added on top of the scaffold: `vitest@5.1.0`, `@vitejs/plugin-react@6.2.1`, `@testing-library/react@16.3.3` + `@testing-library/jest-dom@7.0.1`, `jsdom@31.0.0`, `prettier@3.9.10`, `eslint-config-prettier@10.1.8`. Full pinned graph is in `apps/web/pnpm-lock.yaml`, committed.
- `package.json` sets `"type": "module"` to match the ESM config files (`vitest.config.ts`, `eslint.config.mjs`) and silence a Vite config-loader warning.
- **Next.js 16 ships Cache Components by default** — see `docs/DECISIONS.md` ADR-009 for what that changed about how `apps/web/src/app/app/[orgId]/dashboard/page.tsx` and its layout are written.

## T03 notes — 2026-11-05

- **Python 3.12.8** (`/usr/local/bin/python3.12`), matching the blueprint's "Python 3.12" baseline exactly. `pyproject.toml` pins `requires-python = ">=3.12,<3.13"`; `uv.lock` records `requires-python = "==3.12.*"`.
- **uv 0.13.7**, installed via `brew install uv` since it wasn't already present. Note: that `brew install` triggered Homebrew's automatic `brew cleanup`, which removed several formulae it considered orphaned (`node`, `mongosh`, `libuv`, `brotli`, `c-ares`, `openssl@3`, `icu4c@77`, `libnghttp2`). Confirmed this did not affect the actual Node toolchain (managed via `nvm`, untouched); if any of those were relied on directly via Homebrew, `brew install <name>` restores them.
- Dependencies resolved by `uv lock` (not hand-pinned) — key versions as actually locked: `fastapi==0.162.0`, `uvicorn==0.54.0`, `pydantic==2.14.3` / `pydantic-settings==2.16.2`, `langgraph==1.2.20` (+ `langgraph-checkpoint==4.3.1`, `langgraph-prebuilt==1.1.1`, `langgraph-sdk==0.4.13`, `langchain-core==1.7.6`), `psycopg==3.3.8` (with `psycopg-binary` for local/dev), `redis==8.1.0`. Dev-only: `pytest==9.2.0`, `pytest-asyncio==1.4.1.post1`, `httpx==0.28.1`, `ruff==0.17.4`. Full pinned graph (62 packages) is in `services/agent/uv.lock`, committed.
- `Dockerfile` pins `uv==0.13.7` to match the locally verified version, for build reproducibility.

## T01 notes — 2026-11-05

- `pgvector/pgvector:pg16` — official image maintained by the pgvector project (https://github.com/pgvector/pgvector), PostgreSQL 16 with the `vector` extension's shared library preinstalled. No digest pin yet (no registry access verified from this environment to resolve a current digest); pin to a specific digest once Docker is available and the image has been pulled and tested, per the "actively verify before locking" rule above.
- `redis:7-alpine` — official Redis 7.x image, Alpine base for small footprint. Matches `docs/DEPENDENCY_DECISIONS.md` baseline target "Redis 7.x".
- Neither image's `extversion`/exact patch version has been confirmed by actually running the container in this environment (Docker is not installed here — see Gate H0 in `docs/MANUAL_SETUP.md`). Confirm the running `vector` extension version and exact Redis version string via `scripts/check-infra.sh` / `redis-cli INFO server` once infra is started for the first time, and record them here.
