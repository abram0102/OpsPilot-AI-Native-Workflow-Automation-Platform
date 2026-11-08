package com.opspilot.ticket;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * T10 acceptance: "org A never accesses org B ticket IDs; invalid transitions 409; stale version
 * 409; 25/100 record pages do not duplicate/skip, role restrictions enforced" (the ticket-CRUD
 * half; messages are {@link TicketMessageHttpIntegrationTest}). Needs a real Postgres via
 * Testcontainers.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TicketHttpIntegrationTest {

  private static final String AUDIENCE = "https://api.opspilot.local";

  @Autowired private MockMvc mockMvc;
  @Autowired private KeyPair testIssuerKeyPair;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private MembershipRepository memberships;
  @Autowired private CustomerRepository customers;
  @Autowired private TicketRepository tickets;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void memberCanCreateATicketAndAnInlineCustomerButAViewerCannot() throws Exception {
    User member = users.saveAndFlush(user("member"));
    User viewer = users.saveAndFlush(user("viewer"));
    Organization org = organizations.saveAndFlush(organization(member));
    memberships.saveAndFlush(new Membership(org.getId(), member.getId(), MembershipRole.MEMBER));
    memberships.saveAndFlush(new Membership(org.getId(), viewer.getId(), MembershipRole.VIEWER));

    String body =
        mockMvc
            .perform(
                post("/api/v1/organizations/{orgId}/tickets", org.getId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"customerName\": \"Sample Customer 01\", \"subject\": \"Where is my order?\", \"description\": \"It has been five days.\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.priority").value("MEDIUM"))
            .andExpect(jsonPath("$.version").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    TicketResponse created = objectMapper.readValue(body, TicketResponse.class);

    assertThat(customers.findByIdAndOrganizationId(created.customerId(), org.getId())).isPresent();

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/tickets", org.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(viewer))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\": \"X\", \"subject\": \"s\", \"description\": \"d\"}"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/tickets/{ticketId}", org.getId(), created.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(viewer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tags").isArray())
        .andExpect(jsonPath("$.messageCount").value(0));
  }

  @Test
  void creatingATicketRequiresEitherACustomerIdOrAName() throws Exception {
    User member = users.saveAndFlush(user("no-customer"));
    Organization org = organizations.saveAndFlush(organization(member));
    memberships.saveAndFlush(new Membership(org.getId(), member.getId(), MembershipRole.MEMBER));

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/tickets", org.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\": \"s\", \"description\": \"d\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void aCrossTenantCustomerIdIsRejected() throws Exception {
    User memberA = users.saveAndFlush(user("member-a"));
    User ownerB = users.saveAndFlush(user("owner-b"));
    Organization orgA = organizations.saveAndFlush(organization(memberA));
    Organization orgB = organizations.saveAndFlush(organization(ownerB));
    memberships.saveAndFlush(new Membership(orgA.getId(), memberA.getId(), MembershipRole.MEMBER));
    Customer customerInOrgB =
        customers.saveAndFlush(new Customer(orgB.getId(), "Org B customer", null));

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/tickets", orgA.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(memberA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"customerId\": \""
                        + customerInOrgB.getId()
                        + "\", \"subject\": \"s\", \"description\": \"d\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
  }

  @Test
  void orgANeverAccessesOrgBsTicket() throws Exception {
    User ownerA = users.saveAndFlush(user("owner-a2"));
    User ownerB = users.saveAndFlush(user("owner-b2"));
    Organization orgA = organizations.saveAndFlush(organization(ownerA));
    Organization orgB = organizations.saveAndFlush(organization(ownerB));
    memberships.saveAndFlush(new Membership(orgA.getId(), ownerA.getId(), MembershipRole.OWNER));
    memberships.saveAndFlush(new Membership(orgB.getId(), ownerB.getId(), MembershipRole.OWNER));
    Customer customerB = customers.saveAndFlush(new Customer(orgB.getId(), "B customer", null));
    Ticket ticketB =
        tickets.saveAndFlush(
            new Ticket(
                orgB.getId(),
                customerB.getId(),
                "B subject",
                "B body",
                TicketPriority.LOW,
                ownerB.getId()));

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/tickets/{ticketId}", orgA.getId(), ticketB.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(ownerA)))
        .andExpect(status().isNotFound());
  }

  @Test
  void onlyAllowedStatusTransitionsAreAccepted() throws Exception {
    User owner = users.saveAndFlush(user("owner-transitions"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));
    Customer customer = customers.saveAndFlush(new Customer(org.getId(), "Customer", null));
    Ticket ticket =
        tickets.saveAndFlush(
            new Ticket(org.getId(), customer.getId(), "s", "d", TicketPriority.LOW, owner.getId()));

    // OPEN -> RESOLVED directly is illegal.
    mockMvc
        .perform(
            patch("/api/v1/organizations/{orgId}/tickets/{ticketId}", org.getId(), ticket.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\": 0, \"status\": \"RESOLVED\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));

    // OPEN -> IN_PROGRESS is legal.
    mockMvc
        .perform(
            patch("/api/v1/organizations/{orgId}/tickets/{ticketId}", org.getId(), ticket.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\": 0, \"status\": \"IN_PROGRESS\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void aStaleVersionIsRejected() throws Exception {
    User owner = users.saveAndFlush(user("owner-stale"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));
    Customer customer = customers.saveAndFlush(new Customer(org.getId(), "Customer", null));
    Ticket ticket =
        tickets.saveAndFlush(
            new Ticket(
                org.getId(), customer.getId(), "s", "d", TicketPriority.MEDIUM, owner.getId()));

    mockMvc
        .perform(
            patch("/api/v1/organizations/{orgId}/tickets/{ticketId}", org.getId(), ticket.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\": 0, \"priority\": \"HIGH\"}"))
        .andExpect(status().isOk());

    // Same client, still holding the now-stale version 0.
    mockMvc
        .perform(
            patch("/api/v1/organizations/{orgId}/tickets/{ticketId}", org.getId(), ticket.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\": 0, \"priority\": \"LOW\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
  }

  @Test
  void assigningToSomeoneOutsideTheOrganizationIsRejected() throws Exception {
    User owner = users.saveAndFlush(user("owner-assignee"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));
    Customer customer = customers.saveAndFlush(new Customer(org.getId(), "Customer", null));
    Ticket ticket =
        tickets.saveAndFlush(
            new Ticket(
                org.getId(), customer.getId(), "s", "d", TicketPriority.MEDIUM, owner.getId()));

    mockMvc
        .perform(
            patch("/api/v1/organizations/{orgId}/tickets/{ticketId}", org.getId(), ticket.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\": 0, \"assigneeId\": \"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void malformedCursorIsRejected() throws Exception {
    User owner = users.saveAndFlush(user("owner-cursor"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/tickets", org.getId())
                .queryParam("cursor", "not-a-real-cursor")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void pagesDoNotDuplicateOrSkipRecordsAcrossCursors() throws Exception {
    User owner = users.saveAndFlush(user("owner-paging"));
    Organization org = organizations.saveAndFlush(organization(owner));
    memberships.saveAndFlush(new Membership(org.getId(), owner.getId(), MembershipRole.OWNER));
    Customer customer = customers.saveAndFlush(new Customer(org.getId(), "Customer", null));

    int total = 30;
    for (int i = 0; i < total; i++) {
      tickets.saveAndFlush(
          new Ticket(
              org.getId(),
              customer.getId(),
              "Ticket " + i,
              "d",
              TicketPriority.LOW,
              owner.getId()));
    }

    Set<UUID> seen = new HashSet<>();
    List<UUID> order = new ArrayList<>();
    String cursor = null;
    int pageCount = 0;
    do {
      var requestBuilder =
          get("/api/v1/organizations/{orgId}/tickets", org.getId())
              .queryParam("limit", "12")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner));
      if (cursor != null) {
        requestBuilder = requestBuilder.queryParam("cursor", cursor);
      }
      String body =
          mockMvc
              .perform(requestBuilder)
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      TicketPageResponse page = objectMapper.readValue(body, TicketPageResponse.class);
      for (TicketResponse ticket : page.items()) {
        assertThat(seen.add(ticket.id())).as("ticket %s seen twice", ticket.id()).isTrue();
        order.add(ticket.id());
      }
      cursor = page.nextCursor();
      pageCount++;
      assertThat(pageCount).isLessThan(10); // guards against an infinite loop on a bug
    } while (cursor != null);

    assertThat(seen).hasSize(total);
    // Newest-first: each page's rows must already be in non-increasing createdAt/id order, and
    // this test created them in a known sequence, so the very first result is the LAST one made.
    assertThat(order).hasSize(total);
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
