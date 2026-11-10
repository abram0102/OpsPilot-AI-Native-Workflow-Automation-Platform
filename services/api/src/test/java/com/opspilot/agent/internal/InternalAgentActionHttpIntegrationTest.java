package com.opspilot.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opspilot.TestcontainersConfiguration;
import com.opspilot.agent.AgentActionType;
import com.opspilot.agent.AgentRun;
import com.opspilot.agent.AgentRunRepository;
import com.opspilot.agent.Approval;
import com.opspilot.agent.ApprovalRepository;
import com.opspilot.agent.ApprovalService;
import com.opspilot.agent.ApprovalStatus;
import com.opspilot.agent.ToolExecutionRepository;
import com.opspilot.agent.ToolExecutionStatus;
import com.opspilot.agent.Workflow;
import com.opspilot.audit.AuditEventRepository;
import com.opspilot.auth.User;
import com.opspilot.auth.UserRepository;
import com.opspilot.common.security.TestIssuerConfig;
import com.opspilot.common.security.TestJwtSigner;
import com.opspilot.common.security.TestServiceJwtSigner;
import com.opspilot.organization.Membership;
import com.opspilot.organization.MembershipRepository;
import com.opspilot.organization.MembershipRole;
import com.opspilot.organization.Organization;
import com.opspilot.organization.OrganizationRepository;
import com.opspilot.ticket.Customer;
import com.opspilot.ticket.CustomerRepository;
import com.opspilot.ticket.Ticket;
import com.opspilot.ticket.TicketMessageRepository;
import com.opspilot.ticket.TicketPriority;
import com.opspilot.ticket.TicketRepository;
import com.opspilot.ticket.TicketTagRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * T21 acceptance: "any unknown action denied; browser bearer cannot call internal endpoint; org
 * mismatch denied; fabricated approval denied; tool execution logged and sanitized; no arbitrary
 * command/SQL tool."
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InternalAgentActionHttpIntegrationTest {

  private static final String USER_AUDIENCE = "https://api.opspilot.local";
  private static final String ENDPOINT = "/internal/v1/agent-actions/execute";

  @Autowired private MockMvc mockMvc;
  @Autowired private KeyPair testIssuerKeyPair;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private MembershipRepository memberships;
  @Autowired private CustomerRepository customers;
  @Autowired private TicketRepository tickets;
  @Autowired private TicketTagRepository ticketTags;
  @Autowired private TicketMessageRepository ticketMessages;
  @Autowired private AgentRunRepository runs;
  @Autowired private ApprovalRepository approvals;
  @Autowired private ApprovalService approvalService;
  @Autowired private ToolExecutionRepository executions;
  @Autowired private AuditEventRepository auditEvents;

  private UUID orgId;
  private UUID otherOrgId;
  private UUID ticketId;
  private UUID adminId;
  private AgentRun run;

  @BeforeEach
  void seed() {
    User admin = users.saveAndFlush(user("admin"));
    adminId = admin.getId();
    Organization org = organizations.saveAndFlush(organization(admin));
    orgId = org.getId();
    memberships.saveAndFlush(new Membership(orgId, adminId, MembershipRole.ADMIN));

    User outsider = users.saveAndFlush(user("outsider"));
    otherOrgId = organizations.saveAndFlush(organization(outsider)).getId();

    Customer customer =
        customers.saveAndFlush(new Customer(orgId, "Ada", "ada-" + UUID.randomUUID() + "@x.test"));
    Ticket ticket =
        tickets.saveAndFlush(
            new Ticket(orgId, customer.getId(), "Subject", "Body", TicketPriority.LOW, adminId));
    ticketId = ticket.getId();

    run =
        runs.saveAndFlush(new AgentRun(orgId, ticketId, adminId, Workflow.SUPPORT_TRIAGE_V1, null));
  }

  private String body(UUID runId, String toolCallId, String actionType, String argumentsJson) {
    return """
    {"runId":"%s","toolCallId":"%s","actionType":"%s","arguments":%s}
    """
        .formatted(runId, toolCallId, actionType, argumentsJson);
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder asWorker(
      String json) {
    return post(ENDPOINT)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestServiceJwtSigner.validWorkerToken())
        .contentType(MediaType.APPLICATION_JSON)
        .content(json);
  }

  // --- Identity -------------------------------------------------------------------------

  @Test
  void aBrowserUserTokenCannotCallTheInternalEndpoint() throws Exception {
    // The acceptance criterion this endpoint exists to satisfy. The user token is perfectly valid
    // for every public endpoint; it is rejected here on signature and audience, not on a role.
    String userToken =
        TestJwtSigner.sign(
            testIssuerKeyPair,
            TestIssuerConfig.TEST_ISSUER,
            users.findAll().get(0).getAuthSubject(),
            USER_AUDIENCE,
            Instant.now().plusSeconds(60));

    mockMvc
        .perform(
            post(ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(run.getId(), "call-1", "ADD_INTERNAL_TAG", "{\"tag\":\"shipping\"}")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void noTokenAtAllIsRejected() throws Exception {
    mockMvc
        .perform(
            post(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(run.getId(), "call-1", "ADD_INTERNAL_TAG", "{\"tag\":\"shipping\"}")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aServiceTokenSignedWithTheWrongSecretIsRejected() throws Exception {
    String forged =
        TestServiceJwtSigner.sign(
            "not-the-configured-secret-at-all-padded-to-length",
            "opspilot-agent-worker",
            TestServiceJwtSigner.SERVICE_AUDIENCE,
            Instant.now().plusSeconds(60));

    mockMvc
        .perform(
            post(ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(run.getId(), "call-1", "ADD_INTERNAL_TAG", "{\"tag\":\"shipping\"}")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aServiceTokenForTheUserAudienceIsRejected() throws Exception {
    // Right key, wrong audience: the audience split is what separates a worker credential from a
    // user one, so it must be enforced independently of the signature.
    String wrongAudience =
        TestServiceJwtSigner.sign(
            TestServiceJwtSigner.LOCAL_SECRET,
            "opspilot-agent-worker",
            USER_AUDIENCE,
            Instant.now().plusSeconds(60));

    mockMvc
        .perform(
            post(ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongAudience)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(run.getId(), "call-1", "ADD_INTERNAL_TAG", "{\"tag\":\"shipping\"}")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void anExpiredServiceTokenIsRejected() throws Exception {
    String expired =
        TestServiceJwtSigner.sign(
            TestServiceJwtSigner.LOCAL_SECRET,
            "opspilot-agent-worker",
            TestServiceJwtSigner.SERVICE_AUDIENCE,
            Instant.now().minusSeconds(60));

    mockMvc
        .perform(
            post(ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(run.getId(), "call-1", "ADD_INTERNAL_TAG", "{\"tag\":\"shipping\"}")))
        .andExpect(status().isUnauthorized());
  }

  // --- Allowlist ------------------------------------------------------------------------

  @Test
  void anUnknownActionTypeIsRejectedBeforeReachingAnyPolicy() throws Exception {
    // An invented action cannot even deserialize into the enum, so it never reaches the service.
    mockMvc
        .perform(asWorker(body(run.getId(), "call-1", "DELETE_ALL_TICKETS", "{}")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void thereIsNoArbitraryCommandOrSqlAction() throws Exception {
    for (String invented : new String[] {"RUN_SQL", "EXECUTE_SHELL", "FETCH_URL", "GRANT_ROLE"}) {
      mockMvc
          .perform(asWorker(body(run.getId(), "call-x", invented, "{}")))
          .andExpect(status().isBadRequest());
    }
  }

  // --- Tenant reconstruction ------------------------------------------------------------

  @Test
  void anUnknownRunIsRejected() throws Exception {
    mockMvc
        .perform(
            asWorker(
                body(UUID.randomUUID(), "call-1", "ADD_INTERNAL_TAG", "{\"tag\":\"shipping\"}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
  }

  @Test
  void aRunFromAnotherOrganizationActsOnlyInItsOwnTenant() throws Exception {
    // The worker cannot name an organization at all; the tag lands on the *other* org's ticket
    // because that run says so, and never on this org's ticket.
    User otherUser = users.saveAndFlush(user("other"));
    Customer otherCustomer =
        customers.saveAndFlush(
            new Customer(otherOrgId, "Bob", "bob-" + UUID.randomUUID() + "@x.test"));
    Ticket otherTicket =
        tickets.saveAndFlush(
            new Ticket(
                otherOrgId,
                otherCustomer.getId(),
                "S",
                "B",
                TicketPriority.LOW,
                otherUser.getId()));
    AgentRun otherRun =
        runs.saveAndFlush(
            new AgentRun(
                otherOrgId,
                otherTicket.getId(),
                otherUser.getId(),
                Workflow.SUPPORT_TRIAGE_V1,
                null));

    mockMvc
        .perform(
            asWorker(
                body(otherRun.getId(), "call-1", "ADD_INTERNAL_TAG", "{\"tag\":\"shipping\"}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));

    assertThat(ticketTags.findAllByTicketIdAndOrganizationId(ticketId, orgId)).isEmpty();
    assertThat(ticketTags.findAllByTicketIdAndOrganizationId(otherTicket.getId(), otherOrgId))
        .hasSize(1);
  }

  // --- ADD_INTERNAL_TAG -------------------------------------------------------------------

  @Test
  void anAllowlistedTagIsAppliedWithoutAnyApproval() throws Exception {
    mockMvc
        .perform(
            asWorker(body(run.getId(), "call-1", "ADD_INTERNAL_TAG", "{\"tag\":\"shipping\"}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.alreadyApplied").value(false))
        .andExpect(
            jsonPath("$.resultReference").value(org.hamcrest.Matchers.startsWith("ticket_tag:")));

    assertThat(ticketTags.findAllByTicketIdAndOrganizationId(ticketId, orgId))
        .extracting(tag -> tag.getTag())
        .containsExactly("shipping");
  }

  @Test
  void anUnlistedTagIsRejectedEvenThoughItIsWellFormed() throws Exception {
    mockMvc
        .perform(
            asWorker(
                body(run.getId(), "call-1", "ADD_INTERNAL_TAG", "{\"tag\":\"escalate-to-ceo\"}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));

    assertThat(ticketTags.findAllByTicketIdAndOrganizationId(ticketId, orgId)).isEmpty();
  }

  @Test
  void aMalformedTagIsRejected() throws Exception {
    mockMvc
        .perform(
            asWorker(
                body(
                    run.getId(),
                    "call-1",
                    "ADD_INTERNAL_TAG",
                    "{\"tag\":\"shipping; DROP TABLE tickets\"}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
  }

  @Test
  void aTagProposalCarryingExtraArgumentsIsRejected() throws Exception {
    mockMvc
        .perform(
            asWorker(
                body(
                    run.getId(),
                    "call-1",
                    "ADD_INTERNAL_TAG",
                    "{\"tag\":\"shipping\",\"ticketId\":\"" + UUID.randomUUID() + "\"}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
  }

  // --- PUBLISH_INTERNAL_REPLY -------------------------------------------------------------

  @Test
  void publishingWithoutAnApprovalIsRejected() throws Exception {
    mockMvc
        .perform(
            asWorker(
                body(
                    run.getId(),
                    "call-1",
                    "PUBLISH_INTERNAL_REPLY",
                    "{\"approvalId\":\"" + UUID.randomUUID() + "\"}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));

    assertThat(
            ticketMessages.findAllByTicketIdAndOrganizationIdOrderByCreatedAtAsc(ticketId, orgId))
        .isEmpty();
  }

  @Test
  void aFabricatedApprovalIdIsDenied() throws Exception {
    // The acceptance criterion. A random uuid is not an approval, and the endpoint re-reads it
    // rather than taking the worker's word that one exists.
    mockMvc
        .perform(
            asWorker(
                body(
                    run.getId(),
                    "call-1",
                    "PUBLISH_INTERNAL_REPLY",
                    "{\"approvalId\":\"" + UUID.randomUUID() + "\"}")))
        .andExpect(jsonPath("$.status").value("REJECTED"));
  }

  @Test
  void aPendingApprovalDoesNotAuthorizePublication() throws Exception {
    Approval approval = proposeApproval("call-1", "A drafted reply.");

    mockMvc
        .perform(
            asWorker(
                body(
                    run.getId(),
                    "call-1",
                    "PUBLISH_INTERNAL_REPLY",
                    "{\"approvalId\":\"" + approval.getId() + "\"}")))
        .andExpect(jsonPath("$.status").value("REJECTED"));

    assertThat(
            ticketMessages.findAllByTicketIdAndOrganizationIdOrderByCreatedAtAsc(ticketId, orgId))
        .isEmpty();
  }

  @Test
  void aRejectedApprovalDoesNotAuthorizePublication() throws Exception {
    Approval approval = proposeApproval("call-1", "A drafted reply.");
    approvalService.decide(orgId, approval.getId(), ApprovalStatus.REJECTED, adminId);

    mockMvc
        .perform(
            asWorker(
                body(
                    run.getId(),
                    "call-1",
                    "PUBLISH_INTERNAL_REPLY",
                    "{\"approvalId\":\"" + approval.getId() + "\"}")))
        .andExpect(jsonPath("$.status").value("REJECTED"));
  }

  @Test
  void anApprovalForADifferentToolCallDoesNotAuthorizeThisOne() throws Exception {
    Approval other = proposeApproval("call-OTHER", "A drafted reply.");
    approvalService.decide(orgId, other.getId(), ApprovalStatus.APPROVED, adminId);

    mockMvc
        .perform(
            asWorker(
                body(
                    run.getId(),
                    "call-1",
                    "PUBLISH_INTERNAL_REPLY",
                    "{\"approvalId\":\"" + other.getId() + "\"}")))
        .andExpect(jsonPath("$.status").value("REJECTED"));
  }

  @Test
  void anApprovedProposalPublishesItsOwnStoredText() throws Exception {
    // The single most important behaviour here: what gets published is the text a human approved,
    // read from the immutable proposal — not anything the worker sent.
    Approval approval = proposeApproval("call-1", "Your parcel is on its way.");
    approvalService.decide(orgId, approval.getId(), ApprovalStatus.APPROVED, adminId);

    mockMvc
        .perform(
            asWorker(
                body(
                    run.getId(),
                    "call-1",
                    "PUBLISH_INTERNAL_REPLY",
                    "{\"approvalId\":\"" + approval.getId() + "\"}")))
        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(
            jsonPath("$.resultReference")
                .value(org.hamcrest.Matchers.startsWith("ticket_message:")));

    var published =
        ticketMessages.findAllByTicketIdAndOrganizationIdOrderByCreatedAtAsc(ticketId, orgId);
    assertThat(published).hasSize(1);
    assertThat(published.get(0).getBody()).isEqualTo("Your parcel is on its way.");
    assertThat(published.get(0).getAuthorType().name()).isEqualTo("AGENT");
    assertThat(published.get(0).getPublishedBy()).isEqualTo(adminId);
    assertThat(published.get(0).getSourceRunId()).isEqualTo(run.getId());
  }

  @Test
  void aPublishProposalCannotSmuggleItsOwnBody() throws Exception {
    Approval approval = proposeApproval("call-1", "The approved text.");
    approvalService.decide(orgId, approval.getId(), ApprovalStatus.APPROVED, adminId);

    mockMvc
        .perform(
            asWorker(
                body(
                    run.getId(),
                    "call-1",
                    "PUBLISH_INTERNAL_REPLY",
                    "{\"approvalId\":\"" + approval.getId() + "\",\"body\":\"Refund approved!\"}")))
        .andExpect(jsonPath("$.status").value("REJECTED"));

    assertThat(
            ticketMessages.findAllByTicketIdAndOrganizationIdOrderByCreatedAtAsc(ticketId, orgId))
        .isEmpty();
  }

  // --- Idempotency ------------------------------------------------------------------------

  @Test
  void aReplayedCallReturnsTheFirstOutcomeAndAppliesNothingTwice() throws Exception {
    String json = body(run.getId(), "call-1", "ADD_INTERNAL_TAG", "{\"tag\":\"shipping\"}");

    mockMvc.perform(asWorker(json)).andExpect(jsonPath("$.alreadyApplied").value(false));
    mockMvc
        .perform(asWorker(json))
        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.alreadyApplied").value(true));

    assertThat(ticketTags.findAllByTicketIdAndOrganizationId(ticketId, orgId)).hasSize(1);
    assertThat(executions.findAllByRunIdAndOrganizationIdOrderByCreatedAt(run.getId(), orgId))
        .hasSize(1);
  }

  @Test
  void aReplayedPublicationPublishesOnlyOnce() throws Exception {
    Approval approval = proposeApproval("call-1", "Only once, please.");
    approvalService.decide(orgId, approval.getId(), ApprovalStatus.APPROVED, adminId);
    String json =
        body(
            run.getId(),
            "call-1",
            "PUBLISH_INTERNAL_REPLY",
            "{\"approvalId\":\"" + approval.getId() + "\"}");

    mockMvc.perform(asWorker(json)).andExpect(jsonPath("$.status").value("SUCCEEDED"));
    mockMvc.perform(asWorker(json)).andExpect(jsonPath("$.alreadyApplied").value(true));

    // "Unique (run_id,tool_call_id) and a single DB transaction prevent double publication after
    // retries" — the thing that must never happen twice.
    assertThat(
            ticketMessages.findAllByTicketIdAndOrganizationIdOrderByCreatedAtAsc(ticketId, orgId))
        .hasSize(1);
  }

  // --- Audit ---------------------------------------------------------------------------------

  @Test
  void aSuccessfulActionIsAuditedAsTheRunRatherThanAUser() throws Exception {
    mockMvc.perform(
        asWorker(body(run.getId(), "call-1", "ADD_INTERNAL_TAG", "{\"tag\":\"shipping\"}")));

    var events = auditEvents.findAllByOrganizationIdOrderByOccurredAtDesc(orgId);

    assertThat(events).isNotEmpty();
    var event = events.get(0);
    assertThat(event.getEventType()).isEqualTo("agent.action.executed");
    // An AI-authored change stays distinguishable from a human one forever after.
    assertThat(event.getActorType().name()).isEqualTo("AGENT");
    assertThat(event.getActorId()).isEqualTo(run.getId());
  }

  @Test
  void aRejectedActionIsAuditedToo() throws Exception {
    mockMvc.perform(
        asWorker(body(run.getId(), "call-1", "ADD_INTERNAL_TAG", "{\"tag\":\"not-allowed\"}")));

    assertThat(auditEvents.findAllByOrganizationIdOrderByOccurredAtDesc(orgId))
        .extracting(e -> e.getEventType())
        .contains("agent.action.rejected");
  }

  @Test
  void theAuditMetadataCarriesIdentifiersRatherThanPublishedContent() throws Exception {
    Approval approval = proposeApproval("call-1", "Sensitive customer reply text.");
    approvalService.decide(orgId, approval.getId(), ApprovalStatus.APPROVED, adminId);

    mockMvc.perform(
        asWorker(
            body(
                run.getId(),
                "call-1",
                "PUBLISH_INTERNAL_REPLY",
                "{\"approvalId\":\"" + approval.getId() + "\"}")));

    String metadata =
        auditEvents.findAllByOrganizationIdOrderByOccurredAtDesc(orgId).get(0).getMetadata();

    // Sanitized: an audit row duplicating the reply's text would copy tenant content into a table
    // with a different retention story.
    assertThat(metadata).doesNotContain("Sensitive customer reply text.");
    assertThat(metadata).contains("call-1");
  }

  @Test
  void theExecutionRecordEndsSucceeded() throws Exception {
    mockMvc.perform(
        asWorker(body(run.getId(), "call-1", "ADD_INTERNAL_TAG", "{\"tag\":\"shipping\"}")));

    var execution = executions.findByRunIdAndToolCallId(run.getId(), "call-1").orElseThrow();

    assertThat(execution.getStatus()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
    assertThat(execution.getResultRef()).startsWith("ticket_tag:");
  }

  private Approval proposeApproval(String toolCallId, String body) {
    return approvalService.propose(
        orgId,
        run.getId(),
        ticketId,
        toolCallId,
        AgentActionType.PUBLISH_INTERNAL_REPLY,
        "{\"body\":\"" + body + "\"}");
  }

  private static User user(String label) {
    String unique = UUID.randomUUID().toString();
    return new User("auth|" + label + "-" + unique, label + "-" + unique + "@example.com", label);
  }

  private static Organization organization(User owner) {
    return new Organization("Test Org", "test-org-" + UUID.randomUUID(), owner.getId());
  }
}
