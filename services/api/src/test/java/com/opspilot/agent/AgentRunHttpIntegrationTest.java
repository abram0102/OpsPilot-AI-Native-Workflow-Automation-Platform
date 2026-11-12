package com.opspilot.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opspilot.TestcontainersConfiguration;
import com.opspilot.auth.User;
import com.opspilot.auth.UserRepository;
import com.opspilot.common.outbox.OutboxEventRepository;
import com.opspilot.common.security.TestIssuerConfig;
import com.opspilot.common.security.TestJwtSigner;
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
import java.security.KeyPair;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * T18 acceptance: "client sees runId immediately; DB has outbox event in same transaction; run
 * status visible; double-click start with same idempotency key gives one run; Org B access denied."
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Tag("security")
class AgentRunHttpIntegrationTest {

  private static final String AUDIENCE = "https://api.opspilot.local";
  private static final String BODY = "{\"workflow\":\"SUPPORT_TRIAGE_V1\"}";

  @Autowired private MockMvc mockMvc;
  @Autowired private KeyPair testIssuerKeyPair;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private MembershipRepository memberships;
  @Autowired private CustomerRepository customers;
  @Autowired private TicketRepository tickets;
  @Autowired private AgentRunRepository runs;
  @Autowired private AgentStepRepository steps;
  @Autowired private OutboxEventRepository outboxEvents;
  @Autowired private AgentRunService runService;

  private User member;
  private User admin;
  private User viewer;
  private User outsider;
  private UUID orgId;
  private UUID otherOrgId;
  private UUID ticketId;

  @BeforeEach
  void seed() {
    member = users.saveAndFlush(user("member"));
    admin = users.saveAndFlush(user("admin"));
    viewer = users.saveAndFlush(user("viewer"));
    outsider = users.saveAndFlush(user("outsider"));

    Organization org = organizations.saveAndFlush(organization(member));
    orgId = org.getId();
    memberships.saveAndFlush(new Membership(orgId, member.getId(), MembershipRole.MEMBER));
    memberships.saveAndFlush(new Membership(orgId, admin.getId(), MembershipRole.ADMIN));
    memberships.saveAndFlush(new Membership(orgId, viewer.getId(), MembershipRole.VIEWER));

    Organization otherOrg = organizations.saveAndFlush(organization(outsider));
    otherOrgId = otherOrg.getId();
    memberships.saveAndFlush(new Membership(otherOrgId, outsider.getId(), MembershipRole.OWNER));

    Customer customer =
        customers.saveAndFlush(new Customer(orgId, "Ada", "ada-" + UUID.randomUUID() + "@x.test"));
    ticketId =
        tickets
            .saveAndFlush(
                new Ticket(
                    orgId,
                    customer.getId(),
                    "Where is my order?",
                    "Body",
                    TicketPriority.LOW,
                    member.getId()))
            .getId();
  }

  @Test
  void startingARunReturns202WithTheRunIdImmediately() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/tickets/{ticketId}/runs", orgId, ticketId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.runId").isNotEmpty())
        .andExpect(jsonPath("$.status").value("QUEUED"))
        // The client is told where to look next; it has no other way to know.
        .andExpect(jsonPath("$.pollUrl").value(org.hamcrest.Matchers.containsString("/runs/")));
  }

  @Test
  void startingARunWritesItsOutboxEventInTheSameTransaction() throws Exception {
    String response = startRun(member, null);
    UUID runId = runIdOf(response);

    // Both the run and its event, or neither: a queued run nobody is told about and an event
    // pointing at no run are each worse than a failure.
    assertThat(runs.findByIdAndOrganizationId(runId, orgId)).isPresent();
    assertThat(outboxEvents.countByAggregateTypeAndAggregateId("agent_run", runId)).isEqualTo(1);
  }

  @Test
  void theOutboxEventNamesTheContractsEventTypeAndCarriesOnlyIdentifiers() throws Exception {
    UUID runId = runIdOf(startRun(member, null));

    var event =
        outboxEvents.findAll().stream()
            .filter(e -> e.getAggregateId().equals(runId))
            .findFirst()
            .orElseThrow();

    assertThat(event.getEventType()).isEqualTo("agent.run.requested");
    assertThat(event.getOrganizationId()).isEqualTo(orgId);
    // docs/API_CONTRACT.md: "Published event payload only contains identifiers; workers reload
    // trusted DB state."
    assertThat(event.getPayload()).contains(runId.toString()).contains(ticketId.toString());
  }

  @Test
  void aRepeatedStartWithTheSameIdempotencyKeyGivesOneRun() throws Exception {
    String key = UUID.randomUUID().toString();

    UUID first = runIdOf(startRun(member, key));
    UUID second = runIdOf(startRun(member, key));

    assertThat(second).isEqualTo(first);
    assertThat(runs.findAllByOrganizationIdAndTicketIdOrderByCreatedAtDesc(orgId, ticketId))
        .hasSize(1);
    // One run means one event, or the worker would do the work twice.
    assertThat(outboxEvents.countByAggregateTypeAndAggregateId("agent_run", first)).isEqualTo(1);
  }

  @Test
  void reusingAnIdempotencyKeyForADifferentRequestIsAConflict() throws Exception {
    String key = UUID.randomUUID().toString();
    startRun(member, key);

    Customer other =
        customers.saveAndFlush(new Customer(orgId, "Bob", "bob-" + UUID.randomUUID() + "@x.test"));
    UUID otherTicketId =
        tickets
            .saveAndFlush(
                new Ticket(
                    orgId, other.getId(), "Other", "Body", TicketPriority.LOW, member.getId()))
            .getId();

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/tickets/{ticketId}/runs", orgId, otherTicketId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isConflict())
        // Its own stable code, distinct from STATE_CONFLICT — clients branch on the code.
        .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
  }

  @Test
  void twoStartsWithoutAKeyCreateTwoRuns() throws Exception {
    startRun(member, null);
    startRun(member, null);

    assertThat(runs.findAllByOrganizationIdAndTicketIdOrderByCreatedAtDesc(orgId, ticketId))
        .hasSize(2);
  }

  @Test
  void aViewerCannotStartARun() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/tickets/{ticketId}/runs", orgId, ticketId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(viewer))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  void anUnknownWorkflowIsRejectedAtTheEdge() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/tickets/{ticketId}/runs", orgId, ticketId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflow\":\"DELETE_EVERYTHING\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aTicketFromAnotherOrganizationIsConcealedAsNotFound() throws Exception {
    // The outsider owns another org; starting a run there against this org's ticket id must not
    // reveal that the ticket exists.
    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/tickets/{ticketId}/runs", otherOrgId, ticketId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(outsider))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isNotFound());
  }

  @Test
  void aForeignOrganizationsRunIsNotReadable() throws Exception {
    UUID runId = runIdOf(startRun(member, null));

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/runs/{runId}", otherOrgId, runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(outsider)))
        .andExpect(status().isNotFound());
  }

  @Test
  void aNonMemberCannotListRuns() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/runs", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(outsider)))
        .andExpect(status().isForbidden());
  }

  @Test
  void aViewerCanSeeRunStatusAndDetail() throws Exception {
    UUID runId = runIdOf(startRun(member, null));

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/runs/{runId}", orgId, runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(viewer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("QUEUED"))
        .andExpect(jsonPath("$.workflow").value("SUPPORT_TRIAGE_V1"))
        .andExpect(jsonPath("$.ticketId").value(ticketId.toString()));
  }

  @Test
  void theRunListIsFilterableByStatusAndKeysetPaginated() throws Exception {
    for (int i = 0; i < 3; i++) {
      startRun(member, null);
    }

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/runs", orgId)
                .param("status", "QUEUED")
                .param("limit", "2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.nextCursor").isNotEmpty());

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/runs", orgId)
                .param("status", "COMPLETED")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  void theRunListCanBeNarrowedToOneTicket() throws Exception {
    // Two tickets in the same tenant, so a ticket filter that silently did nothing would still
    // return rows and look like it worked.
    Customer other =
        customers.saveAndFlush(new Customer(orgId, "Bob", "bob-" + UUID.randomUUID() + "@x.test"));
    UUID secondTicketId =
        tickets
            .saveAndFlush(
                new Ticket(
                    orgId,
                    other.getId(),
                    "Second ticket",
                    "Body",
                    TicketPriority.LOW,
                    member.getId()))
            .getId();
    startRun(member, null);
    UUID secondRunId = runIdOf(startRunOn(member, orgId, secondTicketId, null));

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/runs", orgId)
                .param("ticketId", secondTicketId.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(secondRunId.toString()))
        .andExpect(jsonPath("$.items[0].ticketId").value(secondTicketId.toString()));
  }

  @Test
  void theUnfilteredRunListNeverIncludesAnotherOrganizationsRuns() throws Exception {
    // Added by T28's deliberate-regression drill (docs/PROGRESS.md). Dropping `organizationId`
    // from the keyset page queries was caught by exactly one test — the ticket-filter one below —
    // because every other list test seeds runs in a single tenant, and the membership check
    // (which is a separate mechanism) kept the sweep's stranger out. So the plain list, the call
    // the UI actually makes on every page load, had no direct cross-tenant assertion at all.
    //
    // Two tenants, both with a run, listed by a member of one of them. If the tenant predicate
    // ever leaves the query again, this fails on the obvious path rather than only on a filtered
    // one.
    Customer foreign =
        customers.saveAndFlush(
            new Customer(otherOrgId, "Mallory", "mallory-" + UUID.randomUUID() + "@x.test"));
    UUID foreignTicketId =
        tickets
            .saveAndFlush(
                new Ticket(
                    otherOrgId,
                    foreign.getId(),
                    "Other tenant's ticket",
                    "Body",
                    TicketPriority.LOW,
                    outsider.getId()))
            .getId();
    UUID foreignRunId = runIdOf(startRunOn(outsider, otherOrgId, foreignTicketId, null));
    UUID ownRunId = runIdOf(startRun(member, null));

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/runs", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(ownRunId.toString()))
        // Named explicitly: a length assertion alone would still pass if the page happened to be
        // capped at one row while containing the wrong one.
        .andExpect(jsonPath("$.items[?(@.id=='" + foreignRunId + "')]").isEmpty());
  }

  @Test
  void aTicketFilterFromAnotherOrganizationRevealsNothing() throws Exception {
    // ticketId is client-supplied and filtered inside the query, alongside organization_id — so
    // another tenant's ticket yields an empty page rather than that tenant's runs.
    Customer foreign =
        customers.saveAndFlush(
            new Customer(otherOrgId, "Eve", "eve-" + UUID.randomUUID() + "@x.test"));
    UUID foreignTicketId =
        tickets
            .saveAndFlush(
                new Ticket(
                    otherOrgId,
                    foreign.getId(),
                    "Other tenant's ticket",
                    "Body",
                    TicketPriority.LOW,
                    outsider.getId()))
            .getId();
    startRunOn(outsider, otherOrgId, foreignTicketId, null);
    startRun(member, null);

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/runs", orgId)
                .param("ticketId", foreignTicketId.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  void anInvalidCursorIsRejected() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/runs", orgId)
                .param("cursor", "not-a-real-cursor")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void theInitiatorCanCancelTheirOwnRun() throws Exception {
    UUID runId = runIdOf(startRun(member, null));

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/runs/{runId}/cancel", orgId, runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void anAdminCanCancelSomeoneElsesRun() throws Exception {
    UUID runId = runIdOf(startRun(member, null));

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/runs/{runId}/cancel", orgId, runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void aMemberCannotCancelARunTheyDidNotStart() throws Exception {
    // docs/API_CONTRACT.md scopes cancel to "owner/admin/member initiator".
    User otherMember = users.saveAndFlush(user("member2"));
    memberships.saveAndFlush(new Membership(orgId, otherMember.getId(), MembershipRole.MEMBER));
    UUID runId = runIdOf(startRun(member, null));

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/runs/{runId}/cancel", orgId, runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(otherMember)))
        .andExpect(status().isForbidden());
  }

  @Test
  void cancellingAnAlreadyFinishedRunIsAStateConflict() throws Exception {
    UUID runId = runIdOf(startRun(member, null));
    runService.transition(orgId, runId, AgentRunStatus.CANCELLED, null);

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/runs/{runId}/cancel", orgId, runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
  }

  @Test
  void aRunsTraceIsVisibleAndOrdered() throws Exception {
    UUID runId = runIdOf(startRun(member, null));
    // The test-only harness stands in for the worker that T24/T25 will provide.
    new DirectRunExecutorHarness(runService, steps).execute(orgId, runId);

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/runs/{runId}/steps", orgId, runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(viewer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.items[0].sequence").value(0))
        .andExpect(jsonPath("$.items[0].stepType").value("CLASSIFY"))
        .andExpect(jsonPath("$.items[2].stepType").value("FINALIZE"));
  }

  @Test
  void theDirectHarnessPersistsStatusAndUsageOnTheRun() throws Exception {
    UUID runId = runIdOf(startRun(member, null));

    AgentRun finished = new DirectRunExecutorHarness(runService, steps).execute(orgId, runId);

    assertThat(finished.getStatus()).isEqualTo(AgentRunStatus.COMPLETED);
    assertThat(finished.getStepsUsed()).isEqualTo(3);
    assertThat(finished.getTotalInputTokens()).isEqualTo(120);

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/runs/{runId}", orgId, runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member)))
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.stepsUsed").value(3));
  }

  @Test
  void theDirectHarnessCanLeaveARunWaitingOnAHuman() throws Exception {
    UUID runId = runIdOf(startRun(member, null));

    AgentRun paused =
        new DirectRunExecutorHarness(runService, steps).executeUntilApproval(orgId, runId);

    assertThat(paused.getStatus()).isEqualTo(AgentRunStatus.AWAITING_APPROVAL);
  }

  @Test
  void stepsOfAForeignOrganizationsRunAreNotReadable() throws Exception {
    UUID runId = runIdOf(startRun(member, null));

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/runs/{runId}/steps", otherOrgId, runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(outsider)))
        .andExpect(status().isNotFound());
  }

  private String startRun(User as, String idempotencyKey) throws Exception {
    return startRunOn(as, orgId, ticketId, idempotencyKey);
  }

  private String startRunOn(User as, UUID onOrgId, UUID onTicketId, String idempotencyKey)
      throws Exception {
    var request =
        post("/api/v1/organizations/{orgId}/tickets/{ticketId}/runs", onOrgId, onTicketId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(as))
            .contentType(MediaType.APPLICATION_JSON)
            .content(BODY);
    if (idempotencyKey != null) {
      request = request.header("Idempotency-Key", idempotencyKey);
    }
    return mockMvc
        .perform(request)
        .andExpect(status().isAccepted())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static UUID runIdOf(String json) {
    int start = json.indexOf("\"runId\":\"") + 9;
    return UUID.fromString(json.substring(start, start + 36));
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
