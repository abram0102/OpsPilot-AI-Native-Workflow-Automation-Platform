package com.opspilot.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.opspilot.TestcontainersConfiguration;
import com.opspilot.auth.User;
import com.opspilot.auth.UserRepository;
import com.opspilot.organization.Organization;
import com.opspilot.organization.OrganizationRepository;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves docs/DATA_MODEL.md section B.2's literal example — "a ticket in Org A cannot reference a
 * customer in Org B" — is enforced by the database itself (the composite FK in V002__tickets.sql),
 * not only by application code that could have a bug. Needs a real Postgres via Testcontainers.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@Tag("security")
class TicketRepositoryIntegrationTest {

  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private CustomerRepository customers;
  @Autowired private TicketRepository tickets;

  @Test
  void ticketCannotReferenceACustomerFromAnotherOrganization() {
    User user =
        users.saveAndFlush(
            new User("auth|cross-tenant-" + UUID.randomUUID(), "x@example.com", "X"));
    Organization orgA =
        organizations.saveAndFlush(
            new Organization("Org A", "org-a-" + UUID.randomUUID(), user.getId()));
    Organization orgB =
        organizations.saveAndFlush(
            new Organization("Org B", "org-b-" + UUID.randomUUID(), user.getId()));
    Customer customerInOrgB =
        customers.saveAndFlush(new Customer(orgB.getId(), "Org B Customer", null));

    Ticket crossTenantTicket =
        new Ticket(
            orgA.getId(),
            customerInOrgB.getId(),
            "Should be rejected",
            "Org A ticket pointing at an Org B customer.",
            TicketPriority.LOW,
            user.getId());

    assertThatThrownBy(() -> tickets.saveAndFlush(crossTenantTicket))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void ticketForTheSameOrganizationAsItsCustomerIsAccepted() {
    User user =
        users.saveAndFlush(new User("auth|same-tenant-" + UUID.randomUUID(), "y@example.com", "Y"));
    Organization org =
        organizations.saveAndFlush(
            new Organization("Org", "org-" + UUID.randomUUID(), user.getId()));
    Customer customer = customers.saveAndFlush(new Customer(org.getId(), "Customer", null));

    Ticket ticket =
        tickets.saveAndFlush(
            new Ticket(
                org.getId(),
                customer.getId(),
                "Where is my order?",
                "Fictional test ticket.",
                TicketPriority.MEDIUM,
                user.getId()));

    assertThat(tickets.findByIdAndOrganizationId(ticket.getId(), org.getId())).isPresent();
    assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
    assertThat(ticket.getVersion()).isZero();
  }
}
