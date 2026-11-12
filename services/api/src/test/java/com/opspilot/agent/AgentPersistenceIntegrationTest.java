package com.opspilot.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.opspilot.TestcontainersConfiguration;
import com.opspilot.auth.User;
import com.opspilot.auth.UserRepository;
import com.opspilot.common.web.BadRequestException;
import com.opspilot.common.web.ConflictException;
import com.opspilot.common.web.NotFoundException;
import com.opspilot.organization.Organization;
import com.opspilot.organization.OrganizationRepository;
import com.opspilot.ticket.Customer;
import com.opspilot.ticket.CustomerRepository;
import com.opspilot.ticket.Ticket;
import com.opspilot.ticket.TicketPriority;
import com.opspilot.ticket.TicketRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * T17 acceptance: "approval and execution uniqueness; invalid transitions rejected by service".
 *
 * <p>Exercises the services rather than HTTP, because T17 deliberately adds no endpoint — every run
 * and approval endpoint belongs to T18/T22, and nothing can create a run until T18 (see
 * docs/DECISIONS.md ADR-041). Needs a real Postgres via Testcontainers, since the uniqueness and
 * check constraints being relied on are the database's.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@Tag("security")
class AgentPersistenceIntegrationTest {

  @Autowired private AgentRunService runService;
  @Autowired private ApprovalService approvalService;
  @Autowired private ToolExecutionService executionService;
  @Autowired private AgentRunRepository runs;
  @Autowired private ApprovalRepository approvals;
  @Autowired private ToolExecutionRepository executions;
  @Autowired private AgentStepRepository steps;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private CustomerRepository customers;
  @Autowired private TicketRepository tickets;

  private UUID orgId;
  private UUID otherOrgId;
  private UUID ticketId;
  private UUID userId;

  @BeforeEach
  void seed() {
    User owner = users.saveAndFlush(user("owner"));
    userId = owner.getId();
    Organization org = organizations.saveAndFlush(organization(owner));
    orgId = org.getId();
    otherOrgId = organizations.saveAndFlush(organization(owner)).getId();

    Customer customer =
        customers.saveAndFlush(new Customer(orgId, "Ada", "ada-" + UUID.randomUUID() + "@x.test"));
    ticketId =
        tickets
            .saveAndFlush(
                new Ticket(orgId, customer.getId(), "Subject", "Body", TicketPriority.LOW, userId))
            .getId();
  }

  private AgentRun newRun() {
    return runs.saveAndFlush(
        new AgentRun(orgId, ticketId, userId, Workflow.SUPPORT_TRIAGE_V1, null));
  }

  @Test
  void aRunStartsQueuedAndWalksTheDocumentedHappyPath() {
    AgentRun run = newRun();
    assertThat(run.getStatus()).isEqualTo(AgentRunStatus.QUEUED);

    runService.transition(orgId, run.getId(), AgentRunStatus.RUNNING, null);
    runService.transition(orgId, run.getId(), AgentRunStatus.AWAITING_APPROVAL, null);
    runService.transition(orgId, run.getId(), AgentRunStatus.RUNNING, null);
    AgentRun completed = runService.transition(orgId, run.getId(), AgentRunStatus.COMPLETED, null);

    assertThat(completed.getStatus()).isEqualTo(AgentRunStatus.COMPLETED);
    // Two entries into RUNNING, so two attempts.
    assertThat(completed.getAttemptCount()).isEqualTo(2);
  }

  @Test
  void aQueuedRunCannotJumpStraightToCompleted() {
    AgentRun run = newRun();

    assertThatThrownBy(
            () -> runService.transition(orgId, run.getId(), AgentRunStatus.COMPLETED, null))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("QUEUED")
        .hasMessageContaining("COMPLETED");
  }

  @Test
  void aTerminalRunNeverMovesAgain() {
    AgentRun run = newRun();
    runService.transition(orgId, run.getId(), AgentRunStatus.RUNNING, null);
    runService.transition(orgId, run.getId(), AgentRunStatus.COMPLETED, null);

    // A late message must not resurrect a finished run.
    for (AgentRunStatus next : AgentRunStatus.values()) {
      assertThatThrownBy(() -> runService.transition(orgId, run.getId(), next, null))
          .isInstanceOf(ConflictException.class);
    }
  }

  @Test
  void aRejectedProposalCompletesTheRunRatherThanFailingIt() {
    // docs/DATA_MODEL.md leaves this to the implementer; ADR-041 records the choice.
    AgentRun run = newRun();
    runService.transition(orgId, run.getId(), AgentRunStatus.RUNNING, null);
    runService.transition(orgId, run.getId(), AgentRunStatus.AWAITING_APPROVAL, null);

    AgentRun outcome = runService.transition(orgId, run.getId(), AgentRunStatus.COMPLETED, null);

    assertThat(outcome.getStatus()).isEqualTo(AgentRunStatus.COMPLETED);
  }

  @Test
  void aFailureRecordsItsErrorCode() {
    AgentRun run = newRun();
    runService.transition(orgId, run.getId(), AgentRunStatus.RUNNING, null);

    AgentRun failed =
        runService.transition(orgId, run.getId(), AgentRunStatus.FAILED, "AI_TIMEOUT");

    assertThat(failed.getLastErrorCode()).isEqualTo("AI_TIMEOUT");
  }

  @Test
  void aRetryReturnsToQueued() {
    AgentRun run = newRun();
    runService.transition(orgId, run.getId(), AgentRunStatus.RUNNING, null);
    runService.transition(orgId, run.getId(), AgentRunStatus.RETRY_SCHEDULED, "AI_RATE_LIMITED");

    AgentRun requeued = runService.transition(orgId, run.getId(), AgentRunStatus.QUEUED, null);

    assertThat(requeued.getStatus()).isEqualTo(AgentRunStatus.QUEUED);
  }

  @Test
  void aRunInAnotherOrganizationIsInvisible() {
    AgentRun run = newRun();

    assertThatThrownBy(() -> runService.require(otherOrgId, run.getId()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void claimingTakesAnExpiringLease() {
    AgentRun run = newRun();

    AgentRun leased = runService.claimLease(orgId, run.getId(), "worker-1", Duration.ofMinutes(5));

    assertThat(leased.getLeaseOwner()).isEqualTo("worker-1");
    assertThat(leased.isLeasedAt(Instant.now())).isTrue();
  }

  @Test
  void aSecondWorkerCannotStealALiveLease() {
    AgentRun run = newRun();
    runService.claimLease(orgId, run.getId(), "worker-1", Duration.ofMinutes(5));

    assertThatThrownBy(
            () -> runService.claimLease(orgId, run.getId(), "worker-2", Duration.ofMinutes(5)))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void theHolderMayExtendItsOwnLease() {
    AgentRun run = newRun();
    runService.claimLease(orgId, run.getId(), "worker-1", Duration.ofMinutes(1));

    assertThatCode(
            () -> runService.claimLease(orgId, run.getId(), "worker-1", Duration.ofMinutes(5)))
        .doesNotThrowAnyException();
  }

  @Test
  void anExpiredLeaseIsReclaimableByAnyone() {
    AgentRun run = newRun();
    // A lease already in the past is exactly the state a crashed worker leaves behind.
    runService.claimLease(orgId, run.getId(), "worker-1", Duration.ofSeconds(-1));

    AgentRun reclaimed =
        runService.claimLease(orgId, run.getId(), "worker-2", Duration.ofMinutes(5));

    assertThat(reclaimed.getLeaseOwner()).isEqualTo("worker-2");
  }

  @Test
  void anExpiredLeaseMakesTheRunVisibleToTheReclaimer() {
    AgentRun run = newRun();
    runService.claimLease(orgId, run.getId(), "worker-1", Duration.ofSeconds(-1));

    assertThat(runService.findClaimable(Instant.now()))
        .extracting(AgentRun::getId)
        .contains(run.getId());
  }

  @Test
  void aLiveLeaseHidesTheRunFromTheReclaimer() {
    AgentRun run = newRun();
    runService.claimLease(orgId, run.getId(), "worker-1", Duration.ofMinutes(5));

    assertThat(runService.findClaimable(Instant.now()))
        .extracting(AgentRun::getId)
        .doesNotContain(run.getId());
  }

  @Test
  void finishingARunReleasesItsLease() {
    AgentRun run = newRun();
    runService.claimLease(orgId, run.getId(), "worker-1", Duration.ofMinutes(5));
    runService.transition(orgId, run.getId(), AgentRunStatus.RUNNING, null);

    AgentRun done = runService.transition(orgId, run.getId(), AgentRunStatus.COMPLETED, null);

    assertThat(done.getLeaseOwner()).isNull();
    assertThat(done.getLeaseExpiresAt()).isNull();
  }

  @Test
  void aFinishedRunCannotBeLeased() {
    AgentRun run = newRun();
    runService.transition(orgId, run.getId(), AgentRunStatus.CANCELLED, null);

    assertThatThrownBy(
            () -> runService.claimLease(orgId, run.getId(), "worker-1", Duration.ofMinutes(5)))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void anApprovalIsUniquePerToolCall() {
    AgentRun run = newRun();
    Approval first = propose(run, "call-1");
    Approval second = propose(run, "call-1");

    // Redelivery returns the same gate rather than opening a second one.
    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(approvals.findAllByRunIdAndOrganizationIdOrderByCreatedAt(run.getId(), orgId))
        .hasSize(1);
  }

  @Test
  void theDatabaseItselfRefusesADuplicateApprovalForOneToolCall() {
    AgentRun run = newRun();
    propose(run, "call-1");

    // Not relying on the service check alone: the constraint is the real guarantee
    // (docs/DATA_MODEL.md "Unique constraints are part of idempotency").
    assertThatThrownBy(() ->
            approvals.saveAndFlush(
                new Approval(
                    orgId,
                    run.getId(),
                    ticketId,
                    "call-1",
                    AgentActionType.PUBLISH_INTERNAL_REPLY,
                    "{\"body\":\"other\"}")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void decidingRecordsWhoDecidedAndWhen() {
    Approval approval = propose(newRun(), "call-1");

    Approval approved =
        approvalService.decide(orgId, approval.getId(), ApprovalStatus.APPROVED, userId);

    assertThat(approved.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
    assertThat(approved.getDecisionBy()).isEqualTo(userId);
    assertThat(approved.getDecisionAt()).isNotNull();
  }

  @Test
  void decidingTheSameWayTwiceIsIdempotentRatherThanAnError() {
    // AGENTS.md: "double approval cannot execute a tool twice". ADR-041 chooses the idempotent
    // same-outcome reading over a 409.
    Approval approval = propose(newRun(), "call-1");
    Approval first =
        approvalService.decide(orgId, approval.getId(), ApprovalStatus.APPROVED, userId);

    Approval second =
        approvalService.decide(orgId, approval.getId(), ApprovalStatus.APPROVED, userId);

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(second.getDecisionAt()).isEqualTo(first.getDecisionAt());
  }

  @Test
  void reversingADecisionIsRefused() {
    Approval approval = propose(newRun(), "call-1");
    approvalService.decide(orgId, approval.getId(), ApprovalStatus.APPROVED, userId);

    assertThatThrownBy(
            () -> approvalService.decide(orgId, approval.getId(), ApprovalStatus.REJECTED, userId))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void aDecisionMustBeApprovedOrRejected() {
    Approval approval = propose(newRun(), "call-1");

    assertThatThrownBy(
            () -> approvalService.decide(orgId, approval.getId(), ApprovalStatus.EXPIRED, userId))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void anApprovalMustCarryItsProposal() {
    AgentRun run = newRun();

    assertThatThrownBy(() ->
            approvalService.propose(
                orgId,
                run.getId(),
                ticketId,
                "call-1",
                AgentActionType.PUBLISH_INTERNAL_REPLY,
                "  "))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void aDecidedApprovalCannotThenExpire() {
    Approval approval = propose(newRun(), "call-1");
    approvalService.decide(orgId, approval.getId(), ApprovalStatus.REJECTED, userId);

    assertThatThrownBy(() -> approvalService.expire(orgId, approval.getId()))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void onlyAnApprovedApprovalAuthorizesExecution() {
    Approval pending = propose(newRun(), "call-1");
    assertThat(approvalService.authorizesExecution(orgId, pending.getId())).isFalse();

    approvalService.decide(orgId, pending.getId(), ApprovalStatus.REJECTED, userId);
    assertThat(approvalService.authorizesExecution(orgId, pending.getId())).isFalse();
  }

  @Test
  void anApprovalInAnotherOrganizationIsInvisible() {
    Approval approval = propose(newRun(), "call-1");

    assertThatThrownBy(() -> approvalService.require(otherOrgId, approval.getId()))
        .isInstanceOf(NotFoundException.class);
  }

  private Approval propose(AgentRun run, String toolCallId) {
    return approvalService.propose(
        orgId,
        run.getId(),
        ticketId,
        toolCallId,
        AgentActionType.PUBLISH_INTERNAL_REPLY,
        "{\"body\":\"Proposed reply\"}");
  }

  @Test
  void anExecutionIsUniquePerToolCall() {
    AgentRun run = newRun();
    ToolExecution first =
        executionService.propose(orgId, run.getId(), "call-1", AgentActionType.ADD_INTERNAL_TAG);
    ToolExecution second =
        executionService.propose(orgId, run.getId(), "call-1", AgentActionType.ADD_INTERNAL_TAG);

    // "One effect per logical call": a replay finds the persisted record.
    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(executions.findAllByRunIdAndOrganizationIdOrderByCreatedAt(run.getId(), orgId))
        .hasSize(1);
  }

  @Test
  void theDatabaseItselfRefusesADuplicateExecutionForOneToolCall() {
    AgentRun run = newRun();
    executionService.propose(orgId, run.getId(), "call-1", AgentActionType.ADD_INTERNAL_TAG);

    assertThatThrownBy(() ->
            executions.saveAndFlush(
                new ToolExecution(orgId, run.getId(), "call-1", AgentActionType.ADD_INTERNAL_TAG)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aPendingApprovalCannotAuthorizeAnExecution() {
    AgentRun run = newRun();
    Approval approval =
        approvalService.propose(
            orgId,
            run.getId(),
            ticketId,
            "call-1",
            AgentActionType.PUBLISH_INTERNAL_REPLY,
            "{\"body\":\"x\"}");
    ToolExecution execution =
        executionService.propose(
            orgId, run.getId(), "call-1", AgentActionType.PUBLISH_INTERNAL_REPLY);

    assertThatThrownBy(() -> executionService.authorize(orgId, execution.getId(), approval.getId()))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void aRejectedApprovalCannotAuthorizeAnExecution() {
    AgentRun run = newRun();
    Approval approval =
        approvalService.propose(
            orgId,
            run.getId(),
            ticketId,
            "call-1",
            AgentActionType.PUBLISH_INTERNAL_REPLY,
            "{\"body\":\"x\"}");
    approvalService.decide(orgId, approval.getId(), ApprovalStatus.REJECTED, userId);
    ToolExecution execution =
        executionService.propose(
            orgId, run.getId(), "call-1", AgentActionType.PUBLISH_INTERNAL_REPLY);

    assertThatThrownBy(() -> executionService.authorize(orgId, execution.getId(), approval.getId()))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void anApprovalForADifferentCallCannotAuthorizeThisOne() {
    AgentRun run = newRun();
    Approval other =
        approvalService.propose(
            orgId,
            run.getId(),
            ticketId,
            "call-OTHER",
            AgentActionType.PUBLISH_INTERNAL_REPLY,
            "{\"body\":\"x\"}");
    approvalService.decide(orgId, other.getId(), ApprovalStatus.APPROVED, userId);
    ToolExecution execution =
        executionService.propose(
            orgId, run.getId(), "call-1", AgentActionType.PUBLISH_INTERNAL_REPLY);

    assertThatThrownBy(() -> executionService.authorize(orgId, execution.getId(), other.getId()))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void anApprovedApprovalWalksTheExecutionToSuccess() {
    AgentRun run = newRun();
    Approval approval =
        approvalService.propose(
            orgId,
            run.getId(),
            ticketId,
            "call-1",
            AgentActionType.PUBLISH_INTERNAL_REPLY,
            "{\"body\":\"x\"}");
    approvalService.decide(orgId, approval.getId(), ApprovalStatus.APPROVED, userId);
    ToolExecution execution =
        executionService.propose(
            orgId, run.getId(), "call-1", AgentActionType.PUBLISH_INTERNAL_REPLY);

    executionService.authorize(orgId, execution.getId(), approval.getId());
    executionService.transition(orgId, execution.getId(), ToolExecutionStatus.EXECUTING, null);
    ToolExecution done =
        executionService.transition(
            orgId, execution.getId(), ToolExecutionStatus.SUCCEEDED, "message:123");

    assertThat(done.getStatus()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
    assertThat(done.getResultRef()).isEqualTo("message:123");
    assertThat(done.getApprovalId()).isEqualTo(approval.getId());
  }

  @Test
  void aProposedExecutionCannotSkipStraightToSucceeded() {
    AgentRun run = newRun();
    ToolExecution execution =
        executionService.propose(orgId, run.getId(), "call-1", AgentActionType.ADD_INTERNAL_TAG);

    assertThatThrownBy(() ->
            executionService.transition(
                orgId, execution.getId(), ToolExecutionStatus.SUCCEEDED, null))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void aSucceededExecutionNeverMovesAgain() {
    AgentRun run = newRun();
    Approval approval =
        approvalService.propose(
            orgId,
            run.getId(),
            ticketId,
            "call-1",
            AgentActionType.ADD_INTERNAL_TAG,
            "{\"tag\":\"x\"}");
    approvalService.decide(orgId, approval.getId(), ApprovalStatus.APPROVED, userId);
    ToolExecution execution =
        executionService.propose(orgId, run.getId(), "call-1", AgentActionType.ADD_INTERNAL_TAG);
    executionService.authorize(orgId, execution.getId(), approval.getId());
    executionService.transition(orgId, execution.getId(), ToolExecutionStatus.EXECUTING, null);
    executionService.transition(orgId, execution.getId(), ToolExecutionStatus.SUCCEEDED, "r");

    assertThatThrownBy(() ->
            executionService.transition(
                orgId, execution.getId(), ToolExecutionStatus.EXECUTING, null))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void aRetryableFailureMayBeAttemptedAgainButAPermanentOneMayNot() {
    AgentRun run = newRun();
    ToolExecution retryable = authorizedExecution(run, "call-retry");
    executionService.transition(orgId, retryable.getId(), ToolExecutionStatus.EXECUTING, null);
    executionService.transition(
        orgId, retryable.getId(), ToolExecutionStatus.FAILED_RETRYABLE, null);

    assertThatCode(() ->
            executionService.transition(
                orgId, retryable.getId(), ToolExecutionStatus.EXECUTING, null))
        .doesNotThrowAnyException();

    ToolExecution permanent = authorizedExecution(run, "call-permanent");
    executionService.transition(orgId, permanent.getId(), ToolExecutionStatus.EXECUTING, null);
    executionService.transition(
        orgId, permanent.getId(), ToolExecutionStatus.FAILED_PERMANENT, null);

    assertThatThrownBy(() ->
            executionService.transition(
                orgId, permanent.getId(), ToolExecutionStatus.EXECUTING, null))
        .isInstanceOf(ConflictException.class);
  }

  private ToolExecution authorizedExecution(AgentRun run, String toolCallId) {
    Approval approval =
        approvalService.propose(
            orgId,
            run.getId(),
            ticketId,
            toolCallId,
            AgentActionType.ADD_INTERNAL_TAG,
            "{\"tag\":\"x\"}");
    approvalService.decide(orgId, approval.getId(), ApprovalStatus.APPROVED, userId);
    ToolExecution execution =
        executionService.propose(orgId, run.getId(), toolCallId, AgentActionType.ADD_INTERNAL_TAG);
    return executionService.authorize(orgId, execution.getId(), approval.getId());
  }

  @Test
  void stepsAreUniquePerSequenceWithinARun() {
    AgentRun run = newRun();
    steps.saveAndFlush(
        new AgentStep(orgId, run.getId(), 0, AgentStepType.CLASSIFY, AgentStepStatus.SUCCEEDED));

    assertThatThrownBy(() ->
            steps.saveAndFlush(
                new AgentStep(
                    orgId, run.getId(), 0, AgentStepType.RETRIEVE, AgentStepStatus.STARTED)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void stepsReadBackInSequenceOrder() {
    AgentRun run = newRun();
    steps.saveAndFlush(
        new AgentStep(orgId, run.getId(), 1, AgentStepType.RETRIEVE, AgentStepStatus.SUCCEEDED));
    steps.saveAndFlush(
        new AgentStep(orgId, run.getId(), 0, AgentStepType.CLASSIFY, AgentStepStatus.SUCCEEDED));

    assertThat(steps.findAllByRunIdAndOrganizationIdOrderBySequence(run.getId(), orgId))
        .extracting(AgentStep::getStepType)
        .containsExactly(AgentStepType.CLASSIFY, AgentStepType.RETRIEVE);
  }

  @Test
  void twoRunsInOneOrganizationCannotShareAnIdempotencyKey() {
    runs.saveAndFlush(new AgentRun(orgId, ticketId, userId, Workflow.SUPPORT_TRIAGE_V1, "key-1"));

    // T18's "double-click start with same idempotency key gives one run", enforced in schema.
    assertThatThrownBy(() ->
            runs.saveAndFlush(
                new AgentRun(orgId, ticketId, userId, Workflow.SUPPORT_TRIAGE_V1, "key-1")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void runsWithoutAKeyDoNotCollide() {
    runs.saveAndFlush(new AgentRun(orgId, ticketId, userId, Workflow.SUPPORT_TRIAGE_V1, null));

    assertThatCode(() ->
            runs.saveAndFlush(
                new AgentRun(orgId, ticketId, userId, Workflow.SUPPORT_TRIAGE_V1, null)))
        .doesNotThrowAnyException();
  }

  private static User user(String label) {
    String unique = UUID.randomUUID().toString();
    return new User("auth|" + label + "-" + unique, label + "-" + unique + "@example.com", label);
  }

  private static Organization organization(User owner) {
    return new Organization("Test Org", "test-org-" + UUID.randomUUID(), owner.getId());
  }
}
