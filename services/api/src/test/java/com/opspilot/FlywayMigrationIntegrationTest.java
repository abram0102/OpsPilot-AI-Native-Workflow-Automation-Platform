package com.opspilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Proves the T06 acceptance checks that are actually about Flyway itself: a fresh DB migrates
 * cleanly, and re-validating an already-migrated schema ("startup restart is idempotent" —
 * docs/TASKS.md T06) does not fail. Needs a real Postgres via Testcontainers, hence Docker (see
 * docs/PROGRESS.md T01/T02 for why that's not available in every environment this suite runs in).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FlywayMigrationIntegrationTest {

  @Autowired private Flyway flyway;

  @Test
  void migrationsAppliedCleanlyAndRevalidatingIsSafe() {
    MigrationInfo current = flyway.info().current();

    assertThat(current).isNotNull();
    assertThat(current.getDescription()).isEqualTo("tickets");
    assertThat(flyway.info().applied()).hasSizeGreaterThanOrEqualTo(2);

    // Re-running validate() against an already-migrated schema (what a
    // restarted app instance does) must not throw.
    assertThatCode(flyway::validate).doesNotThrowAnyException();
  }
}
