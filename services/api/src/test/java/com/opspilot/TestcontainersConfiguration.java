package com.opspilot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// public: reused by integration tests in other packages (organization,
// ticket), not just ApiApplicationTests.
//
// Uses pgvector/pgvector:pg16, not the Spring Initializr default of plain
// postgres:latest: V001 runs `CREATE EXTENSION vector`, which plain Postgres
// images don't ship — matches the image infra/docker-compose.yml uses for
// real local dev (docs/DECISIONS.md ADR-007). asCompatibleSubstituteFor
// ("postgres") tells Testcontainers this differently-named image is still a
// valid Postgres to manage.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  /**
   * Skips the container when an external PostgreSQL is supplied instead.
   *
   * <p>Added in T26, for a concrete reason: that ticket's subject is what happens when several
   * transactions race, and a concurrency test that could only ever run in CI would be written blind
   * and debugged one push at a time. With {@code -Dopspilot.test.external-postgres=true} and the
   * usual {@code POSTGRES_*} variables, the same test runs against a local database — which is how
   * the retry bug in {@code ToolExecutionService}'s transition table was found before it reached CI
   * rather than after.
   *
   * <p>Defaults to using the container, so CI and anyone who just runs {@code ./mvnw test} are
   * unaffected and no workflow sets it. If it ever were set where no database exists, the tests
   * fail on connection refused rather than passing while proving nothing.
   */
  @Bean
  @ServiceConnection
  @ConditionalOnProperty(
      name = "opspilot.test.external-postgres",
      havingValue = "false",
      matchIfMissing = true)
  PostgreSQLContainer postgresContainer() {
    DockerImageName pgvectorImage =
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");
    return new PostgreSQLContainer(pgvectorImage);
  }
}
