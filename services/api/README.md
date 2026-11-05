# services/api

Spring Boot 4.1.2 / Java 21 backend. Skeleton implemented in ticket **T02** (`docs/TASKS.md`): error envelope, request-ID propagation, and a deliberately locked-down security posture (only `/actuator/health/**` is reachable; see `com.opspilot.common.security.SecurityConfig`). No business endpoints, entities, or Flyway migrations exist yet — those start at **T06**.

## Running locally

```bash
cp ../../.env.example ../../.env   # if you haven't already, from the repo root
./mvnw spring-boot:run             # requires a reachable Postgres — see infra/ (T01)
```

The app binds to port 8080 (`API_PORT`) and exposes only `/actuator/health/liveness` and `/actuator/health/readiness` without authentication; everything else is denied until ticket T07 adds real OIDC JWT validation and role-based authorization.

## Structure

- `com.opspilot.common.web` — `RequestIdFilter`, `RequestIdSupport`, `ApiErrorResponse`, `GlobalExceptionHandler` (the stable error envelope from `docs/API_CONTRACT.md`).
- `com.opspilot.common.security` — `SecurityConfig` (health-only allowlist; replaced by real auth in T07).
- Module packages (`auth`, `organization`, `ticket`, `knowledge`, `run`, `policy`, `internal`, `dispatch`) are created by the tickets that need them, not speculatively ahead of time.

## Tests

`./mvnw test` runs 11 tests. 10 are Docker/DB-independent (fast unit + MockMvc slice tests) and pass in any environment. The 11th, `ApiApplicationTests.contextLoads`, boots the full app via Testcontainers' Postgres container and therefore needs Docker — see `docs/PROGRESS.md` for current status in environments without Docker installed.

```bash
./mvnw test                          # full suite (needs Docker for ApiApplicationTests)
./mvnw test -Dtest='!ApiApplicationTests'   # everything else, no Docker required
```
