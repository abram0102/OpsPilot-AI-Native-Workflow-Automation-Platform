package com.opspilot.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opspilot.TestcontainersConfiguration;
import com.opspilot.audit.AuditEventRepository;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * T22 acceptance: "member/viewer cannot decide; owner/admin can; repeated approve no double state
 * effect; approval event persisted; rejection recorded and cannot later approve; cross-org approver
 * blocked."
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApprovalHttpIntegrationTest {

  private static final String AUDIENCE = "https://api.opspilot.local";

  @Autowired private MockMvc mockMvc;
  @Autowired private KeyPair testIssuerKeyPair;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private MembershipRepository memberships;
  @Autowired private CustomerRepository customers;
  @Autowired private TicketRepository tickets;
  @Autowired private AgentRunRepository runs;
  @Autowired private ApprovalService approvalService;
  @Autowired private ApprovalRepository approvals;
  @Autowired private OutboxEventRepository outboxEvents;
  @Autowired private AuditEventRepository auditEvents;

  private User owner;
  private User admin;
  private User member;
  private User viewer;
  private User outsider;
  private UUID orgId;
  private UUID otherOrgId;
  private UUID ticketId;
  private AgentRun run;

  @BeforeEach
  void seed() {
    owner = users.saveAndFlush(user("owner"));
    admin = users.saveAndFlush(user("admin"));
    member = users.saveAndFlush(user("member"));
    viewer = users.saveAndFlush(user("viewer"));
    outsider = users.saveAndFlush(user("outsider"));

    Organization org = organizations.saveAndFlush(organization(owner));
    orgId = org.getId();
    memberships.saveAndFlush(new Membership(orgId, owner.getId(), MembershipRole.OWNER));
    memberships.saveAndFlush(new Membership(orgId, admin.getId(), MembershipRole.ADMIN));
    memberships.saveAndFlush(new Membership(orgId, member.getId(), MembershipRole.MEMBER));
    memberships.saveAndFlush(new Membership(orgId, viewer.getId(), MembershipRole.VIEWER));

    otherOrgId = organizations.saveAndFlush(organization(outsider)).getId();
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
                    owner.getId()))
            .getId();
    run =
        runs.saveAndFlush(
            new AgentRun(orgId, ticketId, owner.getId(), Workflow.SUPPORT_TRIAGE_V1, null));
  }

  private Approval pendingApproval(String toolCallId, String proposalJson) {
    return approvalService.propose(
        orgId,
        run.getId(),
        ticketId,
        toolCallId,
        AgentActionType.PUBLISH_INTERNAL_REPLY,
        proposalJson);
  }

  private Approval pendingApproval() {
    return pendingApproval("call-1", "{\"body\":\"Your parcel is on its way.\"}");
  }

  // --- Authorization --------------------------------------------------------------------

  @Test
  void aMemberCannotDecide() throws Exception {
    Approval approval = pendingApproval();

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/approvals/{id}/approve", orgId, approval.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member)))
        .andExpect(status().isForbidden());

    assertThat(approvals.findById(approval.getId()).orElseThrow().getStatus())
        .isEqualTo(ApprovalStatus.PENDING);
  }

  @Test
  void aViewerCannotDecide() throws Exception {
    Approval approval = pendingApproval();

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/approvals/{id}/reject", orgId, approval.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(viewer)))
        .andExpect(status().isForbidden());
  }

  @Test
  void aMemberCannotEvenSeeTheInbox() throws Exception {
    // The inbox lists what the agent proposes to say to customers; the contract scopes it to
    // owner/admin, not to everyone who can read tickets.
    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/approvals", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(member)))
        .andExpect(status().isForbidden());
  }

  @Test
  void anAdminCanApprove() throws Exception {
    Approval approval = pendingApproval();

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/approvals/{id}/approve", orgId, approval.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.decisionBy").value(admin.getId().toString()));
  }

  @Test
  void anOwnerCanApprove() throws Exception {
    Approval approval = pendingApproval();

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/approvals/{id}/approve", orgId, approval.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));
  }

  @Test
  void aCrossOrganizationApproverIsBlocked() throws Exception {
    Approval approval = pendingApproval();

    // The outsider owns another organization. Presenting this approval's real id under their own
    // org must not reach it.
    mockMvc
        .perform(
            post(
                    "/api/v1/organizations/{orgId}/approvals/{id}/approve",
                    otherOrgId,
                    approval.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(outsider)))
        .andExpect(status().isNotFound());

    assertThat(approvals.findById(approval.getId()).orElseThrow().getStatus())
        .isEqualTo(ApprovalStatus.PENDING);
  }

  @Test
  void anApprovalFromAnotherOrganizationIsConcealedOnRead() throws Exception {
    Approval approval = pendingApproval();

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/approvals/{id}", otherOrgId, approval.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(outsider)))
        .andExpect(status().isNotFound());
  }

  // --- The inbox ------------------------------------------------------------------------

  @Test
  void theInboxListsPendingApprovalsWithTheirExactProposal() throws Exception {
    pendingApproval();

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/approvals", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].status").value("PENDING"))
        .andExpect(jsonPath("$[0].proposal.body").value("Your parcel is on its way."))
        .andExpect(jsonPath("$[0].actionType").value("PUBLISH_INTERNAL_REPLY"));
  }

  @Test
  void theInboxCanBeFilteredByStatus() throws Exception {
    Approval approved = pendingApproval("call-1", "{\"body\":\"One.\"}");
    pendingApproval("call-2", "{\"body\":\"Two.\"}");
    approvalService.decide(orgId, approved.getId(), ApprovalStatus.APPROVED, admin.getId());

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/approvals", orgId)
                .param("status", "APPROVED")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(approved.getId().toString()));

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/approvals", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].status").value("PENDING"));
  }

  @Test
  void theDetailViewSurfacesTheCitedEvidence() throws Exception {
    UUID documentId = UUID.randomUUID();
    Approval approval =
        pendingApproval(
            "call-1",
            "{\"body\":\"Delivery takes 3-5 days.\",\"citations\":[{\"documentId\":\""
                + documentId
                + "\",\"chunkIndex\":2,\"title\":\"Shipping FAQ\"}]}");

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/approvals/{id}", orgId, approval.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.proposal.body").value("Delivery takes 3-5 days."))
        .andExpect(jsonPath("$.evidence.length()").value(1))
        .andExpect(jsonPath("$.evidence[0].documentId").value(documentId.toString()))
        .andExpect(jsonPath("$.evidence[0].chunkIndex").value(2))
        .andExpect(jsonPath("$.evidence[0].title").value("Shipping FAQ"));
  }

  @Test
  void anApprovalWithoutCitationsShowsNoEvidenceRatherThanFailing() throws Exception {
    Approval approval = pendingApproval();

    mockMvc
        .perform(
            get("/api/v1/organizations/{orgId}/approvals/{id}", orgId, approval.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.evidence.length()").value(0));
  }

  // --- Decisions ------------------------------------------------------------------------

  @Test
  void anApprovalQueuesExactlyOneResumeEvent() throws Exception {
    Approval approval = pendingApproval();

    approveAs(admin, approval);

    // docs/API_CONTRACT.md: "commits decision + outbox in one transaction".
    assertThat(outboxEvents.countByAggregateTypeAndAggregateId("agent_run", run.getId()))
        .isEqualTo(1);
    assertThat(outboxEvents.findAll())
        .filteredOn(event -> event.getAggregateId().equals(run.getId()))
        .allSatisfy(event -> assertThat(event.getEventType()).isEqualTo("agent.run.resume"));
  }

  @Test
  void aRepeatedApprovalHasNoDoubleStateEffect() throws Exception {
    Approval approval = pendingApproval();

    approveAs(admin, approval);
    Instant firstDecisionAt = approvals.findById(approval.getId()).orElseThrow().getDecisionAt();
    approveAs(admin, approval);

    Approval after = approvals.findById(approval.getId()).orElseThrow();
    assertThat(after.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
    // Same decision, same timestamp, and crucially only one resume event — a double-clicked
    // button must not make the worker publish twice.
    assertThat(after.getDecisionAt()).isEqualTo(firstDecisionAt);
    assertThat(outboxEvents.countByAggregateTypeAndAggregateId("agent_run", run.getId()))
        .isEqualTo(1);
  }

  @Test
  void aRejectionIsRecordedAndCannotLaterBeApproved() throws Exception {
    Approval approval = pendingApproval();

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/approvals/{id}/reject", orgId, approval.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/approvals/{id}/approve", orgId, approval.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));

    assertThat(approvals.findById(approval.getId()).orElseThrow().getStatus())
        .isEqualTo(ApprovalStatus.REJECTED);
  }

  @Test
  void aRejectionAlsoQueuesAResumeSoTheRunCanFinish() throws Exception {
    // A rejected run still has to be told to stop waiting; otherwise it sits in
    // AWAITING_APPROVAL forever.
    Approval approval = pendingApproval();

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/approvals/{id}/reject", orgId, approval.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
        .andExpect(status().isOk());

    assertThat(outboxEvents.countByAggregateTypeAndAggregateId("agent_run", run.getId()))
        .isEqualTo(1);
  }

  @Test
  void aDecisionIsAuditedAgainstTheHumanWhoMadeIt() throws Exception {
    Approval approval = pendingApproval();

    approveAs(admin, approval);

    assertThat(auditEvents.findAllByOrganizationIdOrderByOccurredAtDesc(orgId))
        .anySatisfy(event -> {
          assertThat(event.getEventType()).isEqualTo("approval.approved");
          // A human decided this one — unlike the AGENT-actor rows T21 writes for the worker.
          assertThat(event.getActorType().name()).isEqualTo("USER");
          assertThat(event.getActorId()).isEqualTo(admin.getId());
          assertThat(event.getTargetId()).isEqualTo(approval.getId());
        });
  }

  @Test
  void anUnknownApprovalIsNotFound() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/approvals/{id}/approve", orgId, UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
        .andExpect(status().isNotFound());
  }

  @Test
  void theDecisionEndpointsAcceptNoRequestBody() throws Exception {
    // docs/API_CONTRACT.md: "Approval must not accept a replacement body from frontend." A body is
    // simply ignored — there is no parameter it could reach — so the stored proposal still stands.
    Approval approval = pendingApproval();

    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/approvals/{id}/approve", orgId, approval.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Refund approved, enjoy!\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.proposal.body").value("Your parcel is on its way."));

    assertThat(approvals.findById(approval.getId()).orElseThrow().getProposal())
        .contains("Your parcel is on its way.")
        .doesNotContain("Refund approved");
  }

  private void approveAs(User as, Approval approval) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/organizations/{orgId}/approvals/{id}/approve", orgId, approval.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(as)))
        .andExpect(status().isOk());
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
