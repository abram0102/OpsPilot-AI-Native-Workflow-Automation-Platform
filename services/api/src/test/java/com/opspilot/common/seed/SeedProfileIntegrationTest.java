package com.opspilot.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.opspilot.TestcontainersConfiguration;
import com.opspilot.organization.Organization;
import com.opspilot.organization.OrganizationRepository;
import com.opspilot.ticket.TicketRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ActiveProfiles;

/**
 * That {@code make seed-demo} starts at all.
 *
 * <p>Written because it did not. {@code application-seed.yml} sets {@code web-application-type:
 * none}, and {@code HttpSecurity} is a servlet-scoped bean, so once a second security filter chain
 * existed the whole context failed with "No qualifying bean of type HttpSecurity" and the seeder
 * could never run. Nothing caught it: every other test boots a web application, and the seeder is a
 * command rather than an endpoint, so no HTTP test covers it.
 *
 * <p>{@code webEnvironment = NONE} is the part that matters — it reproduces the condition the
 * profile creates. A test that let Spring start a servlet environment would pass against the broken
 * code.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"local", "seed"})
class SeedProfileIntegrationTest {

  @Autowired private ApplicationContext context;
  @Autowired private OrganizationRepository organizations;
  @Autowired private TicketRepository tickets;

  @Test
  void theSeederRunsAndCreatesItsFictionalTenants() {
    // The seeder is a CommandLineRunner, so by the time the context is up it has already run.
    assertThat(organizations.findAll())
        .extracting(Organization::getName)
        .contains("Acme Demo", "Globex Demo");

    // Fictional by construction — every seeded subject is marked, so demo data can never be
    // mistaken for anything real (AGENTS.md). Scoped to the seeded tenants rather than asserted
    // over every ticket in the database: against an external PostgreSQL (T26's switch) this schema
    // also holds rows other tests left behind, and those are not this test's business.
    List<UUID> seeded =
        organizations.findAll().stream()
            .filter(organization -> organization.getName().endsWith(" Demo"))
            .map(Organization::getId)
            .toList();
    assertThat(seeded).hasSize(2);

    assertThat(
            tickets.findAll().stream()
                .filter(ticket -> seeded.contains(ticket.getOrganizationId()))
                .toList())
        .isNotEmpty()
        .allSatisfy(ticket -> assertThat(ticket.getSubject()).endsWith("(Demo)"));
  }

  @Test
  void theSeedProfileRegistersNoSchedulerSoTheCommandCanActuallyFinish() {
    // T29. `make seed-demo` seeded its tenants, logged that it had, and then hung forever: the
    // `local` profile enables the outbox publisher, `OutboxPublisherSchedule` carries
    // `@EnableScheduling`, and the task scheduler it registers runs non-daemon threads — so the
    // JVM never exited. Interactively that looks like success and you press Ctrl-C, which is why
    // it survived this long; it only became visible when a script waited for the command to
    // return.
    //
    // Asserted on the TaskScheduler rather than on OutboxPublisherSchedule (which is
    // package-private, and is only the thing that pulls scheduling in). The scheduler is the
    // actual cause: no scheduler bean, no non-daemon pool, so the command returns.
    assertThat(context.getBeanNamesForType(TaskScheduler.class))
        .as(
            "a one-shot seeding command must register no task scheduler — its threads are"
                + " non-daemon, so one would keep the JVM alive after the seeding is done")
        .isEmpty();
  }
}
