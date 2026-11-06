package com.opspilot.common.seed;

import com.opspilot.auth.User;
import com.opspilot.auth.UserRepository;
import com.opspilot.organization.Membership;
import com.opspilot.organization.MembershipRepository;
import com.opspilot.organization.MembershipRole;
import com.opspilot.organization.Organization;
import com.opspilot.organization.OrganizationRepository;
import com.opspilot.ticket.Customer;
import com.opspilot.ticket.CustomerRepository;
import com.opspilot.ticket.Ticket;
import com.opspilot.ticket.TicketPriority;
import com.opspilot.ticket.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds two fictional tenants for local development and manual two-tenant testing (`make
 * seed-demo`) — never anything resembling real customer data (AGENTS.md;
 * docs/PRODUCT_AND_ARCHITECTURE.md "no realistic customer PII").
 *
 * <p>Only active under the {@code seed} profile (see application-seed.yml, which also disables the
 * web server so the process exits once this runs). Idempotent: re-running does nothing if the demo
 * orgs already exist, so it is safe to run against a database that already has them.
 */
@Component
@Profile("seed")
public class DemoDataSeeder implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

  private final OrganizationRepository organizations;
  private final UserRepository users;
  private final MembershipRepository memberships;
  private final CustomerRepository customers;
  private final TicketRepository tickets;

  public DemoDataSeeder(
      OrganizationRepository organizations,
      UserRepository users,
      MembershipRepository memberships,
      CustomerRepository customers,
      TicketRepository tickets) {
    this.organizations = organizations;
    this.users = users;
    this.memberships = memberships;
    this.customers = customers;
    this.tickets = tickets;
  }

  @Override
  @Transactional
  public void run(String... args) {
    seedTenant(
        "acme-demo",
        "Acme Demo",
        "seed|acme-owner",
        "owner@acme-demo.example",
        "Acme Owner (Demo)",
        "Riley Chen (Demo Customer)",
        new String[] {"Where is my order? (Demo)", "Refund request for order #4821 (Demo)"});

    seedTenant(
        "globex-demo",
        "Globex Demo",
        "seed|globex-owner",
        "owner@globex-demo.example",
        "Globex Owner (Demo)",
        "Morgan Lee (Demo Customer)",
        new String[] {"Cannot log in to my account (Demo)"});
  }

  private void seedTenant(
      String slug,
      String orgName,
      String ownerAuthSubject,
      String ownerEmail,
      String ownerDisplayName,
      String customerName,
      String[] ticketSubjects) {
    if (organizations.existsBySlug(slug)) {
      log.info("Seed: organization '{}' already exists, skipping.", slug);
      return;
    }

    User owner = users.save(new User(ownerAuthSubject, ownerEmail, ownerDisplayName));
    Organization org = organizations.save(new Organization(orgName, slug, owner.getId()));
    memberships.save(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));
    Customer customer = customers.save(new Customer(org.getId(), customerName, null));

    for (String subject : ticketSubjects) {
      tickets.save(
          new Ticket(
              org.getId(),
              customer.getId(),
              subject,
              "Fictional seed ticket body for local development and demos only.",
              TicketPriority.MEDIUM,
              owner.getId()));
    }

    log.info(
        "Seed: created organization '{}' ({}) with 1 owner, 1 customer, {} ticket(s).",
        orgName,
        org.getId(),
        ticketSubjects.length);
  }
}
