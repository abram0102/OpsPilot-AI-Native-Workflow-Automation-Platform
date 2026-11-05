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

## T01 notes — 2026-11-05

- `pgvector/pgvector:pg16` — official image maintained by the pgvector project (https://github.com/pgvector/pgvector), PostgreSQL 16 with the `vector` extension's shared library preinstalled. No digest pin yet (no registry access verified from this environment to resolve a current digest); pin to a specific digest once Docker is available and the image has been pulled and tested, per the "actively verify before locking" rule above.
- `redis:7-alpine` — official Redis 7.x image, Alpine base for small footprint. Matches `docs/DEPENDENCY_DECISIONS.md` baseline target "Redis 7.x".
- Neither image's `extversion`/exact patch version has been confirmed by actually running the container in this environment (Docker is not installed here — see Gate H0 in `docs/MANUAL_SETUP.md`). Confirm the running `vector` extension version and exact Redis version string via `scripts/check-infra.sh` / `redis-cli INFO server` once infra is started for the first time, and record them here.
