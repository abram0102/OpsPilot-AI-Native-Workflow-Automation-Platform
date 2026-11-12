package com.opspilot.ticket;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opspilot.TestcontainersConfiguration;
import com.opspilot.auth.User;
import com.opspilot.auth.UserRepository;
import com.opspilot.common.security.TestIssuerConfig;
import com.opspilot.common.security.TestJwtSigner;
import com.opspilot.organization.Membership;
import com.opspilot.organization.MembershipRepository;
import com.opspilot.organization.MembershipRole;
import com.opspilot.organization.Organization;
import com.opspilot.organization.OrganizationRepository;
import java.security.KeyPair;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * T10 acceptance (the message half of "ticket CRUD and state transitions"; docs/DATA_MODEL.md: "A
 * ticket may be replied to while OPEN or IN_PROGRESS"). Needs a real Postgres via Testcontainers.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Tag("security")
class TicketMessageHttpIntegrationTest {

  private static final String AUDIENCE = "https://api.opspilot.local";

  @Autowired private MockMvc mockMvc;
  @Autowired private KeyPair testIssuerKeyPair;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private MembershipRepository memberships;
  @Autowired private CustomerRepository customers;
  @Autowired private TicketRepository tickets;

  @Test
  void aMemberCanPostAndReadMessagesOnAnOpenTicketButAViewerCannotPost() throws Exception {
    User member = users.saveAndFlush(user("member-msg"));
    User viewer = users.saveAndFlush(user("viewer-msg"));
    Organization org = organizations.saveAndFlush(organization(member));
    memberships.saveAndFlush(new Membership(org.getId(), member.getId(), MembershipRole.MEMBER));
    memberships.saveAndFlush(new Membership(org.getId(), viewer.getId(), MembershipRole.VIEWER));
    Customer customer = customers.saveAndFlush(new Customer(org.getId(), "Customer", null));
    Ticket ticket =
        tickets.saveAndFlush(
            new Ticket(
                org.getId(), customer.getId(), "s", "d", TicketPriority.MEDIUM, member.getId()));

    mockMvc
        .perform(
            post(
                    "/api/v1/organizations/{orgId}/tickets/{ticketId}/messages",
                    org.getId(),
                    ticket.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(viewer))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\": \"not allowed\"}"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            post(
                    "/api/v1/organizations/{orgId}/tickets/{ticketId}/messages",
                    org.getId(),
                    ticket.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\": \"We are looking into it.\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.authorType").value("USER"))
        .andExpect(jsonPath("$.body").value("We are looking into it."));

    mockMvc
        .perform(
            get(
                    "/api/v1/organizations/{orgId}/tickets/{ticketId}/messages",
                    org.getId(),
                    ticket.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(viewer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].body").value("We are looking into it."));
  }

  @Test
  void aResolvedTicketCannotBeReplied() throws Exception {
    User owner = users.saveAndFlush(user("owner-resolved"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));
    Customer customer = customers.saveAndFlush(new Customer(org.getId(), "Customer", null));
    Ticket ticket =
        tickets.saveAndFlush(
            new Ticket(
                org.getId(), customer.getId(), "s", "d", TicketPriority.MEDIUM, owner.getId()));

    // Walk it to RESOLVED first (OPEN -> IN_PROGRESS -> RESOLVED).
    mockMvc
        .perform(
            patch("/api/v1/organizations/{orgId}/tickets/{ticketId}", org.getId(), ticket.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\": 0, \"status\": \"IN_PROGRESS\"}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            patch("/api/v1/organizations/{orgId}/tickets/{ticketId}", org.getId(), ticket.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\": 1, \"status\": \"RESOLVED\"}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post(
                    "/api/v1/organizations/{orgId}/tickets/{ticketId}/messages",
                    org.getId(),
                    ticket.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\": \"too late\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
  }

  @Test
  void messagesOnAnotherOrganizationsTicketAreNotFound() throws Exception {
    User ownerA = users.saveAndFlush(user("owner-a-msg"));
    User ownerB = users.saveAndFlush(user("owner-b-msg"));
    Organization orgA = organizations.saveAndFlush(organization(ownerA));
    Organization orgB = organizations.saveAndFlush(organization(ownerB));
    memberships.saveAndFlush(new Membership(orgA.getId(), ownerA.getId(), MembershipRole.OWNER));
    memberships.saveAndFlush(new Membership(orgB.getId(), ownerB.getId(), MembershipRole.OWNER));
    Customer customerB = customers.saveAndFlush(new Customer(orgB.getId(), "B customer", null));
    Ticket ticketB =
        tickets.saveAndFlush(
            new Ticket(
                orgB.getId(), customerB.getId(), "s", "d", TicketPriority.LOW, ownerB.getId()));

    mockMvc
        .perform(
            get(
                    "/api/v1/organizations/{orgId}/tickets/{ticketId}/messages",
                    orgA.getId(),
                    ticketB.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(ownerA)))
        .andExpect(status().isNotFound());
  }

  private String tokenFor(User user) {
    return TestJwtSigner.sign(
        testIssuerKeyPair,
        TestIssuerConfig.TEST_ISSUER,
        user.getAuthSubject(),
        AUDIENCE,
        Instant.now().plusSeconds(60));
  }

  private static User user(String label) {
    String unique = UUID.randomUUID().toString();
    return new User("auth|" + label + "-" + unique, label + "-" + unique + "@example.com", label);
  }

  private static Organization organization(User owner) {
    return new Organization("Test Org", "test-org-" + UUID.randomUUID(), owner.getId());
  }
}
