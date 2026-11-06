package com.opspilot;

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

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    DockerImageName pgvectorImage =
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");
    return new PostgreSQLContainer(pgvectorImage);
  }
}
