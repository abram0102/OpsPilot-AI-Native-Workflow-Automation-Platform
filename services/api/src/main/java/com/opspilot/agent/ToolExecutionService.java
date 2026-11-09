package com.opspilot.agent;

import com.opspilot.common.web.ConflictException;
import com.opspilot.common.web.NotFoundException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tool-execution state machine (docs/DATA_MODEL.md section C; docs/TASKS.md T17).
 *
 * <p>"One effect per logical call": every method keys off {@code (runId, toolCallId)}, which the
 * database holds unique. A replayed delivery therefore finds the persisted outcome instead of
 * acting a second time — the behaviour AGENTS.md requires of every business action.
 *
 * <p>No HTTP surface; the executor that calls this is T21's internal API.
 */
@Service
public class ToolExecutionService {

  private static final Map<ToolExecutionStatus, Set<ToolExecutionStatus>> ALLOWED_TRANSITIONS =
      new EnumMap<>(
          Map.of(
              ToolExecutionStatus.PROPOSED,
                  EnumSet.of(ToolExecutionStatus.AUTHORIZED, ToolExecutionStatus.FAILED_PERMANENT),
              ToolExecutionStatus.AUTHORIZED,
                  EnumSet.of(ToolExecutionStatus.EXECUTING, ToolExecutionStatus.FAILED_PERMANENT),
              ToolExecutionStatus.EXECUTING,
                  EnumSet.of(
                      ToolExecutionStatus.SUCCEEDED,
                      ToolExecutionStatus.FAILED_RETRYABLE,
                      ToolExecutionStatus.FAILED_PERMANENT),
              // A retryable failure may be attempted again under the same key; that is the whole
              // distinction from a permanent one.
              ToolExecutionStatus.FAILED_RETRYABLE,
                  EnumSet.of(ToolExecutionStatus.EXECUTING, ToolExecutionStatus.FAILED_PERMANENT),
              ToolExecutionStatus.SUCCEEDED, EnumSet.noneOf(ToolExecutionStatus.class),
              ToolExecutionStatus.FAILED_PERMANENT, EnumSet.noneOf(ToolExecutionStatus.class)));

  private final ToolExecutionRepository executions;
  private final ApprovalService approvals;

  public ToolExecutionService(ToolExecutionRepository executions, ApprovalService approvals) {
    this.executions = executions;
    this.approvals = approvals;
  }

  /** Records a proposed call, or returns the existing record for the same logical call. */
  @Transactional
  public ToolExecution propose(
      UUID organizationId, UUID runId, String toolCallId, AgentActionType actionType) {
    Optional<ToolExecution> existing = executions.findByRunIdAndToolCallId(runId, toolCallId);
    return existing.orElseGet(() ->
        executions.saveAndFlush(new ToolExecution(organizationId, runId, toolCallId, actionType)));
  }

  /**
   * Authorises a proposed call against an approved approval.
   *
   * <p>Refuses unless the approval actually says APPROVED, which is where "a pending approval
   * cannot be executed, rejected approval cannot be executed" is enforced for real rather than
   * assumed from call order.
   */
  @Transactional
  public ToolExecution authorize(UUID organizationId, UUID executionId, UUID approvalId) {
    ToolExecution execution = require(organizationId, executionId);

    Approval approval = approvals.require(organizationId, approvalId);
    if (approval.getStatus() != ApprovalStatus.APPROVED) {
      throw new ConflictException(
          "Cannot authorize an execution against an approval that is "
              + approval.getStatus()
              + ".");
    }
    if (!approval.getRunId().equals(execution.getRunId())
        || !approval.getToolCallId().equals(execution.getToolCallId())) {
      // An approval for a different call must never authorise this one.
      throw new ConflictException("That approval does not cover this tool call.");
    }

    transitionTo(execution, ToolExecutionStatus.AUTHORIZED, null);
    execution.linkApproval(approvalId);
    return executions.saveAndFlush(execution);
  }

  @Transactional
  public ToolExecution transition(
      UUID organizationId, UUID executionId, ToolExecutionStatus next, String resultRef) {
    ToolExecution execution = require(organizationId, executionId);
    transitionTo(execution, next, resultRef);
    return executions.saveAndFlush(execution);
  }

  @Transactional(readOnly = true)
  public ToolExecution require(UUID organizationId, UUID executionId) {
    return executions
        .findByIdAndOrganizationId(executionId, organizationId)
        .orElseThrow(() -> new NotFoundException("No such tool execution."));
  }

  /** The persisted outcome for a logical call, if one exists. What a replay should return. */
  @Transactional(readOnly = true)
  public Optional<ToolExecution> findByToolCall(UUID runId, String toolCallId) {
    return executions.findByRunIdAndToolCallId(runId, toolCallId);
  }

  private void transitionTo(ToolExecution execution, ToolExecutionStatus next, String resultRef) {
    ToolExecutionStatus current = execution.getStatus();
    if (!ALLOWED_TRANSITIONS.get(current).contains(next)) {
      throw new ConflictException(
          "Cannot move a tool execution from " + current + " to " + next + ".");
    }
    execution.applyStatus(next, resultRef);
  }
}
