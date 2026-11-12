package com.opspilot.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.opspilot.TestcontainersConfiguration;
import com.opspilot.agent.AgentActionType;
import com.opspilot.agent.AgentRun;
import com.opspilot.agent.AgentRunRepository;
import com.opspilot.agent.AgentRunStatus;
import com.opspilot.agent.Approval;
import com.opspilot.agent.ApprovalRepository;
import com.opspilot.agent.ApprovalService;
import com.opspilot.agent.ApprovalStatus;
import com.opspilot.agent.ToolExecution;
import com.opspilot.agent.ToolExecutionRepository;
import com.opspilot.agent.ToolExecutionStatus;
import com.opspilot.agent.Workflow;
import com.opspilot.audit.AuditEventRepository;
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
import com.opspilot.ticket.TicketMessage;
import com.opspilot.ticket.TicketMessageRepository;
import com.opspilot.ticket.TicketPriority;
import com.opspilot.ticket.TicketRepository;
import com.opspilot.ticket.TicketTagRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * T26 acceptance: "send 10 concurrent duplicate requests with same call ID -> one message/tag
 * effect; approved action performed only once and audit consistent."
 *
 * <p>Deliberately <em>not</em> {@code @Transactional}, unlike every other test on this endpoint.
 * The whole subject is what happens when several transactions race, and a test-managed rollback
 * would make each thread invisible to the others — the races being guarded against could not occur,
 * and the test would pass whatever the code did.
 *
 * <p>Calls the service rather than HTTP for the same reason: ten concurrent MockMvc requests share
 * a servlet environment whose threading is not the thing under test. The guard lives in the
 * database, so the test has to reach the component that talks to it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Tag("security")
class AgentActionIdempotencyIntegrationTest {

  private static final int CONCURRENT_CALLERS = 10;

  @Autowired private AgentActionExecutionService executionService;
  @Autowired private ApprovalService approvalService;
  @Autowired private AgentRunRepository runs;
  @Autowired private ApprovalRepository approvals;
  @Autowired private ToolExecutionRepository executions;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private MembershipRepository memberships;
  @Autowired private CustomerRepository customers;
  @Autowired private TicketRepository tickets;
  @Autowired private TicketTagRepository ticketTags;
  @Autowired private AuditEventRepository auditEvents;
  @Autowired private JdbcTemplate jdbc;

  /**
   * Spied so one test can make the business mutation fail.
   *
   * <p>Failure injection, not convenience: the realistic cause is the run's ticket being deleted
   * between the service loading it and the insert, which cannot be interleaved deterministically
   * from a test. The spy stands in for exactly that — a {@code DataIntegrityViolationException} out
   * of the repository — and every other test in this class uses the real bean untouched.
   */
  @MockitoSpyBean private TicketMessageRepository ticketMessages;

  private UUID orgId;
  private UUID adminId;
  private UUID ticketId;
  private AgentRun run;

  @BeforeEach
  void seed() {
    Mockito.reset(ticketMessages);
    // Nothing is rolled back for us, so each test starts from a clean slate.
    jdbc.execute(
        "TRUNCATE audit_events, agent_usage, tool_executions, approvals, agent_steps, agent_runs,"
            + " ticket_tags, ticket_messages, tickets, customers, outbox_events, memberships,"
            + " organizations, users RESTART IDENTITY CASCADE");

    User admin = users.saveAndFlush(user("admin"));
    adminId = admin.getId();
    orgId = organizations.saveAndFlush(organization(admin)).getId();
    memberships.saveAndFlush(new Membership(orgId, adminId, MembershipRole.ADMIN));

    Customer customer =
        customers.saveAndFlush(new Customer(orgId, "Ada", "ada-" + UUID.randomUUID() + "@x.test"));
    ticketId =
        tickets
            .saveAndFlush(
                new Ticket(orgId, customer.getId(), "Subject", "Body", TicketPriority.LOW, adminId))
            .getId();
    run =
        runs.saveAndFlush(new AgentRun(orgId, ticketId, adminId, Workflow.SUPPORT_TRIAGE_V1, null));
  }

  // --- ten at once -------------------------------------------------------------------------

  @Test
  void tenConcurrentDuplicatePublishRequestsProduceExactlyOneMessage() throws Exception {
    Approval approval = approve("call-1", "Your parcel is on its way.");

    List<ExecuteAgentActionResponse> responses =
        inParallel(CONCURRENT_CALLERS, () -> executionService.execute(publish("call-1", approval)));

    assertThat(responses).hasSize(CONCURRENT_CALLERS);
    assertThat(responses)
        .allSatisfy(response ->
            assertThat(response.status())
                .isEqualTo(ExecuteAgentActionResponse.ActionStatus.SUCCEEDED));

    // One caller did the work; the other nine were told it had already been done.
    assertThat(responses.stream().filter(r -> !r.alreadyApplied()).count()).isEqualTo(1);
    assertThat(responses.stream().filter(ExecuteAgentActionResponse::alreadyApplied).count())
        .isEqualTo(CONCURRENT_CALLERS - 1L);

    // The effect itself, which is the point: one message, not ten.
    List<TicketMessage> published =
        ticketMessages.findAllByTicketIdAndOrganizationIdOrderByCreatedAtAsc(ticketId, orgId);
    assertThat(published).hasSize(1);
    assertThat(published.getFirst().getBody()).isEqualTo("Your parcel is on its way.");

    // Every response points at that same message.
    assertThat(responses)
        .extracting(ExecuteAgentActionResponse::resultReference)
        .containsOnly("ticket_message:" + published.getFirst().getId());

    assertThat(executions.findAllByRunIdAndOrganizationIdOrderByCreatedAt(run.getId(), orgId))
        .singleElement()
        .satisfies(execution ->
            assertThat(execution.getStatus()).isEqualTo(ToolExecutionStatus.SUCCEEDED));

    // Audit consistency: one executed event for one effect, not one per delivery.
    assertThat(auditEventCount("agent.action.executed")).isEqualTo(1);
  }

  @Test
  void tenConcurrentDuplicateTagRequestsProduceExactlyOneTag() throws Exception {
    List<ExecuteAgentActionResponse> responses =
        inParallel(CONCURRENT_CALLERS, () -> executionService.execute(tag("call-tag", "urgent")));

    assertThat(responses.stream().filter(r -> !r.alreadyApplied()).count()).isEqualTo(1);
    assertThat(ticketTags.findAllByTicketIdAndOrganizationId(ticketId, orgId)).hasSize(1);
    assertThat(auditEventCount("agent.action.executed")).isEqualTo(1);
  }

  @Test
  void tenConcurrentRetriesOfARetryableFailureStillProduceOneEffect() throws Exception {
    // The race the unique index alone does not cover. Once the row exists and is
    // FAILED_RETRYABLE, every caller's insert conflicts instantly and returns without waiting —
    // so without SELECT ... FOR UPDATE all ten would read a non-terminal status and all ten
    // would publish. ticket_messages has no uniqueness to fall back on.
    Approval approval = approve("call-1", "A retried reply.");
    executions.saveAndFlush(
        new ToolExecution(orgId, run.getId(), "call-1", AgentActionType.PUBLISH_INTERNAL_REPLY));
    jdbc.update(
        "UPDATE tool_executions SET status = 'FAILED_RETRYABLE' WHERE run_id = ?", run.getId());

    List<ExecuteAgentActionResponse> responses =
        inParallel(CONCURRENT_CALLERS, () -> executionService.execute(publish("call-1", approval)));

    assertThat(responses.stream().filter(r -> !r.alreadyApplied()).count()).isEqualTo(1);
    assertThat(
            ticketMessages.findAllByTicketIdAndOrganizationIdOrderByCreatedAtAsc(ticketId, orgId))
        .hasSize(1);
  }

  @Test
  void twoDifferentToolCallsBothTakeEffect() throws Exception {
    // The guard must be no wider than one logical call: two genuinely different calls are two
    // effects, and a test that only proved "one message ever" would pass on a broken guard.
    Approval first = approve("call-1", "First reply.");
    Approval second = approve("call-2", "Second reply.");

    executionService.execute(publish("call-1", first));
    executionService.execute(publish("call-2", second));

    assertThat(
            ticketMessages.findAllByTicketIdAndOrganizationIdOrderByCreatedAtAsc(ticketId, orgId))
        .hasSize(2);
    assertThat(auditEventCount("agent.action.executed")).isEqualTo(2);
  }

  // --- replay ------------------------------------------------------------------------------

  @Test
  void aSequentialReplayReturnsTheStoredOutcomeAndActsNoFurther() {
    Approval approval = approve("call-1", "Your parcel is on its way.");

    ExecuteAgentActionResponse first = executionService.execute(publish("call-1", approval));
    ExecuteAgentActionResponse replay = executionService.execute(publish("call-1", approval));

    assertThat(first.alreadyApplied()).isFalse();
    assertThat(replay.alreadyApplied()).isTrue();
    assertThat(replay.resultReference()).isEqualTo(first.resultReference());
    assertThat(
            ticketMessages.findAllByTicketIdAndOrganizationIdOrderByCreatedAtAsc(ticketId, orgId))
        .hasSize(1);
    assertThat(auditEventCount("agent.action.executed")).isEqualTo(1);
  }

  // --- cancel / retry races ------------------------------------------------------------------

  @Test
  void aCancelledRunCannotGainANewEffect() {
    Approval approval = approve("call-1", "Too late.");
    cancelRun();

    ExecuteAgentActionResponse response = executionService.execute(publish("call-1", approval));

    assertThat(response.status()).isEqualTo(ExecuteAgentActionResponse.ActionStatus.REJECTED);
    assertThat(
            ticketMessages.findAllByTicketIdAndOrganizationIdOrderByCreatedAtAsc(ticketId, orgId))
        .isEmpty();
    // Permanently refused, so a later redelivery does not try again.
    assertThat(storedStatus("call-1")).isEqualTo(ToolExecutionStatus.FAILED_PERMANENT);
    assertThat(auditEventCount("agent.action.rejected")).isEqualTo(1);
  }

  @Test
  void anActionAlreadyAppliedKeepsReportingSuccessAfterTheRunIsCancelled() {
    // The effect is out in the world. Reporting it as rejected afterwards would be a lie, and a
    // worker that believed it would retry something that has already happened.
    Approval approval = approve("call-1", "Already sent.");
    ExecuteAgentActionResponse first = executionService.execute(publish("call-1", approval));
    cancelRun();

    ExecuteAgentActionResponse afterCancel = executionService.execute(publish("call-1", approval));

    assertThat(afterCancel.status()).isEqualTo(ExecuteAgentActionResponse.ActionStatus.SUCCEEDED);
    assertThat(afterCancel.alreadyApplied()).isTrue();
    assertThat(afterCancel.resultReference()).isEqualTo(first.resultReference());
    assertThat(
            ticketMessages.findAllByTicketIdAndOrganizationIdOrderByCreatedAtAsc(ticketId, orgId))
        .hasSize(1);
  }

  // --- rollback of a failed mutation ------------------------------------------------------------

  @Test
  void aFailedMutationPublishesNothingAndLeavesADurableRetryableFailure() {
    Approval approval = approve("call-1", "Will not land.");
    Mockito.doThrow(new DataIntegrityViolationException("ticket vanished mid-flight"))
        .when(ticketMessages)
        .saveAndFlush(Mockito.any());

    ExecuteAgentActionResponse response = executionService.execute(publish("call-1", approval));

    assertThat(response.status()).isEqualTo(ExecuteAgentActionResponse.ActionStatus.FAILED);
    Mockito.reset(ticketMessages);
    assertThat(
            ticketMessages.findAllByTicketIdAndOrganizationIdOrderByCreatedAtAsc(ticketId, orgId))
        .isEmpty();
    // Durable, and retryable rather than permanent: the policy and approval checks passed, so what
    // failed was applying the effect.
    assertThat(storedStatus("call-1")).isEqualTo(ToolExecutionStatus.FAILED_RETRYABLE);
    assertThat(auditEventCount("agent.action.failed")).isEqualTo(1);
    assertThat(auditEventCount("agent.action.executed")).isZero();
  }

  @Test
  void aRetryAfterAFailedMutationSucceedsUnderTheSameKey() {
    Approval approval = approve("call-1", "Lands on the second try.");
    Mockito.doThrow(new DataIntegrityViolationException("transient"))
        .when(ticketMessages)
        .saveAndFlush(Mockito.any());
    executionService.execute(publish("call-1", approval));
    Mockito.reset(ticketMessages);

    ExecuteAgentActionResponse retry = executionService.execute(publish("call-1", approval));

    assertThat(retry.status()).isEqualTo(ExecuteAgentActionResponse.ActionStatus.SUCCEEDED);
    assertThat(retry.alreadyApplied()).isFalse();
    assertThat(
            ticketMessages.findAllByTicketIdAndOrganizationIdOrderByCreatedAtAsc(ticketId, orgId))
        .hasSize(1);
    // Still one row: a retry reuses the logical call rather than opening a second one.
    assertThat(executions.findAllByRunIdAndOrganizationIdOrderByCreatedAt(run.getId(), orgId))
        .hasSize(1);
    assertThat(auditEventCount("agent.action.executed")).isEqualTo(1);
  }

  @Test
  void aDatabaseLevelFailureRollsEverythingBackButStillLeavesAnAuditTrail() {
    Approval approval = approve("call-1", "Will not land.");
    Mockito.doAnswer(
            invocation -> {
              // What a real database error does, and what plain doThrow does not: Spring marks the
              // shared transaction rollback-only as the exception leaves the repository's boundary.
              TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
              throw new DataIntegrityViolationException("the run's ticket vanished mid-flight");
            })
        .when(ticketMessages)
        .saveAndFlush(Mockito.any());

    ExecuteAgentActionResponse response = executionService.execute(publish("call-1", approval));
    Mockito.reset(ticketMessages);

    assertThat(response.status()).isEqualTo(ExecuteAgentActionResponse.ActionStatus.FAILED);
    // Atomic, as the ticket asks: the effect, the execution row and the status transitions all go
    // back together. A retry under the same key starts from nothing, which is correct — nothing
    // happened.
    assertThat(
            ticketMessages.findAllByTicketIdAndOrganizationIdOrderByCreatedAtAsc(ticketId, orgId))
        .isEmpty();
    assertThat(executions.findAllByRunIdAndOrganizationIdOrderByCreatedAt(run.getId(), orgId))
        .isEmpty();

    // The part that needed fixing. Before AgentActionFailureAudit this was 0: the failure left no
    // trace whatsoever, because the audit row rolled back with everything else.
    assertThat(auditEventCount("agent.action.failed")).isEqualTo(1);
    assertThat(auditEventCount("agent.action.executed")).isZero();
  }

  @Test
  void aRetryAfterADatabaseLevelFailureStillProducesExactlyOneMessage() {
    // The rollback must leave the system genuinely retryable rather than merely clean.
    Approval approval = approve("call-1", "Lands on the second try.");
    Mockito.doAnswer(
            invocation -> {
              TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
              throw new DataIntegrityViolationException("transient");
            })
        .when(ticketMessages)
        .saveAndFlush(Mockito.any());
    executionService.execute(publish("call-1", approval));
    Mockito.reset(ticketMessages);

    ExecuteAgentActionResponse retry = executionService.execute(publish("call-1", approval));

    assertThat(retry.status()).isEqualTo(ExecuteAgentActionResponse.ActionStatus.SUCCEEDED);
    assertThat(retry.alreadyApplied()).isFalse();
    assertThat(
            ticketMessages.findAllByTicketIdAndOrganizationIdOrderByCreatedAtAsc(ticketId, orgId))
        .hasSize(1);
    assertThat(auditEventCount("agent.action.executed")).isEqualTo(1);
    assertThat(auditEventCount("agent.action.failed")).isEqualTo(1);
  }

  // --- helpers ---------------------------------------------------------------------------------

  /** Runs `work` on `callers` threads released together, and returns every result. */
  private <T> List<T> inParallel(int callers, Callable<T> work) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    CyclicBarrier startTogether = new CyclicBarrier(callers);
    try {
      List<Future<T>> futures =
          pool.invokeAll(
              java.util.stream.IntStream.range(0, callers)
                  .<Callable<T>>mapToObj(i ->
                      () -> {
                        // A barrier rather than staggered starts: the race only happens if they are
                        // genuinely simultaneous, and a loop of sequential calls would prove
                        // nothing.
                        startTogether.await(30, TimeUnit.SECONDS);
                        return work.call();
                      })
                  .toList());

      List<T> results = new java.util.ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(60, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private Approval approve(String toolCallId, String body) {
    Approval approval =
        approvalService.propose(
            orgId,
            run.getId(),
            ticketId,
            toolCallId,
            AgentActionType.PUBLISH_INTERNAL_REPLY,
            "{\"body\":\"" + body + "\"}");
    approvalService.decide(orgId, approval.getId(), ApprovalStatus.APPROVED, adminId);
    return approvals.findByIdAndOrganizationId(approval.getId(), orgId).orElseThrow();
  }

  private ExecuteAgentActionRequest publish(String toolCallId, Approval approval) {
    return new ExecuteAgentActionRequest(
        run.getId(),
        toolCallId,
        AgentActionType.PUBLISH_INTERNAL_REPLY,
        Map.of("approvalId", approval.getId().toString()));
  }

  private ExecuteAgentActionRequest tag(String toolCallId, String tag) {
    return new ExecuteAgentActionRequest(
        run.getId(), toolCallId, AgentActionType.ADD_INTERNAL_TAG, Map.of("tag", tag));
  }

  private void cancelRun() {
    jdbc.update(
        "UPDATE agent_runs SET status = ? WHERE id = ?",
        AgentRunStatus.CANCELLED.name(),
        run.getId());
  }

  private ToolExecutionStatus storedStatus(String toolCallId) {
    return executions
        .findByRunIdAndToolCallId(run.getId(), toolCallId)
        .map(ToolExecution::getStatus)
        .orElseThrow();
  }

  private int auditEventCount(String eventType) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_events WHERE event_type = ? AND organization_id = ?",
            Integer.class,
            eventType,
            orgId);
    return count == null ? 0 : count;
  }

  private static User user(String label) {
    String unique = UUID.randomUUID().toString();
    return new User("auth|" + label + "-" + unique, label + "-" + unique + "@example.com", label);
  }

  private static Organization organization(User owner) {
    return new Organization("Test Org", "test-org-" + UUID.randomUUID(), owner.getId());
  }
}
