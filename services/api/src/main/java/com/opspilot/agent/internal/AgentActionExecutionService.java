package com.opspilot.agent.internal;

import com.opspilot.agent.AgentRun;
import com.opspilot.agent.AgentRunRepository;
import com.opspilot.agent.AgentRunStatus;
import com.opspilot.agent.Approval;
import com.opspilot.agent.ApprovalRepository;
import com.opspilot.agent.ApprovalStatus;
import com.opspilot.agent.ToolExecution;
import com.opspilot.agent.ToolExecutionRepository;
import com.opspilot.agent.ToolExecutionService;
import com.opspilot.agent.ToolExecutionStatus;
import com.opspilot.audit.AuditActorType;
import com.opspilot.audit.AuditEvent;
import com.opspilot.audit.AuditEventRepository;
import com.opspilot.ticket.Ticket;
import com.opspilot.ticket.TicketMessage;
import com.opspilot.ticket.TicketMessageRepository;
import com.opspilot.ticket.TicketRepository;
import com.opspilot.ticket.TicketTag;
import com.opspilot.ticket.TicketTagRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Executes an allowlisted agent action (docs/TASKS.md T21).
 *
 * <p>The governing rule is docs/API_CONTRACT.md's: anything the worker says about organization,
 * ticket or approval state is an "untrusted hint". Only {@code runId}, {@code toolCallId}, {@code
 * actionType} and {@code arguments} are read from the request, and every other fact — which tenant,
 * which ticket, whether an approval exists and says APPROVED — is loaded from the persisted run. A
 * compromised or confused worker can therefore ask for the wrong thing, but cannot assert its way
 * into a different tenant.
 *
 * <p>Idempotent by {@code (run_id, tool_call_id)}: a redelivered call returns the first outcome
 * with {@code alreadyApplied=true} and performs no second effect. That is the constraint AGENTS.md
 * names ("business actions are idempotent by unique (run_id, tool_call_id)"), and it is enforced by
 * the database rather than by checking first and hoping.
 */
@Service
public class AgentActionExecutionService {

  private static final Logger log = LoggerFactory.getLogger(AgentActionExecutionService.class);

  private final AgentRunRepository runs;
  private final ApprovalRepository approvals;
  private final ToolExecutionRepository executions;
  private final ToolExecutionService executionStates;
  private final TicketRepository tickets;
  private final TicketTagRepository ticketTags;
  private final TicketMessageRepository ticketMessages;
  private final AuditEventRepository auditEvents;
  private final AgentActionFailureAudit failureAudit;
  private final ObjectMapper objectMapper;

  public AgentActionExecutionService(
      AgentRunRepository runs,
      ApprovalRepository approvals,
      ToolExecutionRepository executions,
      ToolExecutionService executionStates,
      TicketRepository tickets,
      TicketTagRepository ticketTags,
      TicketMessageRepository ticketMessages,
      AuditEventRepository auditEvents,
      AgentActionFailureAudit failureAudit,
      ObjectMapper objectMapper) {
    this.runs = runs;
    this.approvals = approvals;
    this.executions = executions;
    this.executionStates = executionStates;
    this.tickets = tickets;
    this.ticketTags = ticketTags;
    this.ticketMessages = ticketMessages;
    this.auditEvents = auditEvents;
    this.failureAudit = failureAudit;
    this.objectMapper = objectMapper;
  }

  /** One transaction, so the effect, its execution record and its audit row commit together. */
  @Transactional
  public ExecuteAgentActionResponse execute(ExecuteAgentActionRequest request) {
    String toolCallId = request.toolCallId();

    // The run is the root of trust. No organization id is accepted from the caller, so a run id
    // that does not exist simply has no tenant to act in.
    Optional<AgentRun> maybeRun = runs.findById(request.runId());
    if (maybeRun.isEmpty()) {
      return reject(toolCallId, null, request, "Unknown run.");
    }
    AgentRun run = maybeRun.get();
    UUID organizationId = run.getOrganizationId();

    // T26: claim the row and hold it for the rest of this transaction before deciding anything.
    // Everything below — the replay check, the policy check, the effect — now runs with concurrent
    // deliveries of this same logical call queued behind the row rather than racing through it.
    ToolExecution execution = claimAndLock(organizationId, run, request);

    // A replay returns the stored outcome and acts no further. Read under the lock, so a caller
    // that waited for an in-flight sibling sees that sibling's committed result.
    if (execution.getStatus() == ToolExecutionStatus.SUCCEEDED) {
      return ExecuteAgentActionResponse.succeeded(toolCallId, execution.getResultRef(), true);
    }
    if (execution.getStatus() == ToolExecutionStatus.FAILED_PERMANENT) {
      return ExecuteAgentActionResponse.rejected(toolCallId);
    }
    // Anything mid-flight or retryable falls through and is attempted again under the same key.

    // The cancel/retry race (T26). Checked *after* the replay branch on purpose: an action that
    // already succeeded must keep reporting its outcome even once the run is cancelled, because
    // the effect is out in the world and pretending otherwise would be a lie. What cancellation
    // forbids is a *new* effect.
    if (run.getStatus() == AgentRunStatus.CANCELLED) {
      markFailed(execution, ToolExecutionStatus.FAILED_PERMANENT);
      return reject(toolCallId, organizationId, request, "The run has been cancelled.");
    }

    String policyRejection =
        AgentActionPolicy.rejectionReason(request.actionType(), request.safeArguments());
    if (policyRejection != null) {
      // Permanent by nature: the same arguments will be refused by the same rule for ever, so the
      // row records that rather than leaving a PROPOSED row a retry would pick up again.
      markFailed(execution, ToolExecutionStatus.FAILED_PERMANENT);
      return reject(toolCallId, organizationId, request, policyRejection);
    }

    Ticket ticket =
        tickets.findByIdAndOrganizationId(run.getTicketId(), organizationId).orElse(null);
    if (ticket == null) {
      // The run names a ticket that is gone, or belonged to another tenant all along.
      markFailed(execution, ToolExecutionStatus.FAILED_PERMANENT);
      return reject(toolCallId, organizationId, request, "The run's ticket no longer exists.");
    }

    Approval approval = null;
    if (AgentActionPolicy.requiresApproval(request.actionType())) {
      approval = resolveApproval(request, run, organizationId);
      if (approval == null) {
        markFailed(execution, ToolExecutionStatus.FAILED_PERMANENT);
        return reject(
            toolCallId, organizationId, request, "No approved approval authorizes this action.");
      }
      // Goes through the service so the approval is re-validated against this very execution a
      // second time, by the component that owns that rule.
      execution = executionStates.authorize(organizationId, execution.getId(), approval.getId());
    } else {
      execution = executionStates.authorizeWithoutApproval(organizationId, execution.getId());
    }

    // EXECUTING before the effect, not after. Besides being the truthful order, it is the only
    // one the state machine allows to fail: EXECUTING -> FAILED_RETRYABLE is legal, while
    // AUTHORIZED -> FAILED_RETRYABLE is not, so recording a failure from AUTHORIZED would itself
    // throw inside the catch block below.
    executionStates.transition(
        organizationId, execution.getId(), ToolExecutionStatus.EXECUTING, null);

    try {
      String resultReference =
          switch (request.actionType()) {
            case ADD_INTERNAL_TAG -> applyTag(run, organizationId, ticket, request.safeArguments());
            case PUBLISH_INTERNAL_REPLY -> publishReply(run, organizationId, ticket, approval);
          };

      executionStates.transition(
          organizationId, execution.getId(), ToolExecutionStatus.SUCCEEDED, resultReference);

      recordAudit(
          organizationId,
          run,
          request,
          "agent.action.executed",
          Map.of("toolCallId", toolCallId, "resultReference", resultReference));

      return ExecuteAgentActionResponse.succeeded(toolCallId, resultReference, false);
    } catch (RuntimeException e) {
      // Retryable: the policy and approval checks already passed, so the failure is in applying
      // the effect rather than in whether it was permitted.
      log.warn(
          "Agent action {} failed for run {} (toolCallId={})",
          request.actionType(),
          run.getId(),
          toolCallId,
          e);
      markFailed(execution, ToolExecutionStatus.FAILED_RETRYABLE);
      // In its own transaction, so the record survives even when the failure was a database error
      // that already marked this one rollback-only. See AgentActionFailureAudit for what a probe
      // showed happens otherwise: nothing published, and no trace that anything was attempted.
      failureAudit.recordFailure(organizationId, run.getId(), run.getTicketId(), request);
      return new ExecuteAgentActionResponse(
          toolCallId, ExecuteAgentActionResponse.ActionStatus.FAILED, null, false);
    }
  }

  /**
   * Creates the execution row if nobody has, then locks it.
   *
   * <p>Two mechanisms, because they cover different races. {@code INSERT ... ON CONFLICT DO
   * NOTHING} serializes callers racing to *create* the row: a second delivery arriving while the
   * first transaction is still open blocks on {@code uq_tool_executions_run_tool_call} rather than
   * raising, which is what turns ten simultaneous duplicates into one effect and nine replies
   * reporting the stored outcome. {@code SELECT ... FOR UPDATE} then covers the other race, where
   * the row already exists and is retryable: without it two retries would both read a non-terminal
   * status and both apply the effect.
   *
   * <p>Neither is an in-memory lock, deliberately (AGENTS.md: "DB uniqueness and row-level locks,
   * not in-memory mutexes"). A mutex would hold only within one JVM, and this API runs behind more
   * than one instance.
   */
  private ToolExecution claimAndLock(
      UUID organizationId, AgentRun run, ExecuteAgentActionRequest request) {
    executions.insertIfAbsent(
        organizationId, run.getId(), request.toolCallId(), request.actionType().name());

    return executions
        .lockByRunIdAndToolCallId(run.getId(), request.toolCallId())
        .orElseThrow(() ->
            // Unreachable: the insert above either created the row or found it. If this ever
            // throws, the unique constraint the whole design rests on is missing.
            new IllegalStateException(
                "No tool execution row after claiming it for run " + run.getId() + "."));
  }

  /**
   * Re-reads the approval from the database and re-checks it.
   *
   * <p>The worker supplies only an id. Whether that approval exists, belongs to this run, covers
   * this very tool call and actually says APPROVED are all answered here — which is why a
   * fabricated or borrowed approval id buys nothing.
   */
  private Approval resolveApproval(
      ExecuteAgentActionRequest request, AgentRun run, UUID organizationId) {
    UUID approvalId;
    try {
      approvalId = UUID.fromString(request.safeArguments().get("approvalId"));
    } catch (IllegalArgumentException e) {
      return null;
    }

    Approval approval =
        approvals.findByIdAndOrganizationId(approvalId, organizationId).orElse(null);
    if (approval == null) {
      return null;
    }
    if (!approval.getRunId().equals(run.getId())) {
      return null;
    }
    if (!approval.getToolCallId().equals(request.toolCallId())) {
      // An approval for a different call must never authorise this one, even within the same run.
      return null;
    }
    if (approval.getStatus() != ApprovalStatus.APPROVED) {
      return null;
    }
    return approval;
  }

  private String applyTag(
      AgentRun run, UUID organizationId, Ticket ticket, Map<String, String> arguments) {
    String tag = arguments.get("tag");
    // `uq_ticket_tags_ticket_tag` makes re-tagging a no-op at the database level, so a replay
    // that gets this far still cannot produce a duplicate.
    return ticketTags.findAllByTicketIdAndOrganizationId(ticket.getId(), organizationId).stream()
        .filter(existing -> existing.getTag().equals(tag))
        .findFirst()
        .map(existing -> "ticket_tag:" + existing.getId())
        .orElseGet(() ->
            "ticket_tag:"
                + ticketTags
                    .saveAndFlush(new TicketTag(organizationId, ticket.getId(), tag, run.getId()))
                    .getId());
  }

  /**
   * Publishes the approved proposal's own text.
   *
   * <p>The body comes from the stored proposal, never from the request — docs/API_CONTRACT.md:
   * "content taken from immutable approved proposal, not worker request". This is the single most
   * important line in the method: it is what makes the thing a human approved and the thing that
   * gets published the same thing.
   */
  private String publishReply(AgentRun run, UUID organizationId, Ticket ticket, Approval approval) {
    String body = extractProposalBody(approval);

    TicketMessage message = TicketMessage.fromAgent(organizationId, ticket.getId(), body);
    message.markPublished(approval.getDecisionBy(), run.getId());

    return "ticket_message:" + ticketMessages.saveAndFlush(message).getId();
  }

  private String extractProposalBody(Approval approval) {
    var proposal = objectMapper.readTree(approval.getProposal());
    var body = proposal.get("body");
    if (body == null || body.stringValue() == null || body.stringValue().isBlank()) {
      throw new IllegalStateException("The approved proposal carries no reply body.");
    }
    return body.stringValue();
  }

  private void markFailed(ToolExecution execution, ToolExecutionStatus status) {
    // Re-read rather than trusting the local reference's status, and stay silent if the row is
    // already terminal — a failure path must not fail.
    ToolExecution current =
        executionStates.require(execution.getOrganizationId(), execution.getId());
    if (current.getStatus().isTerminal()) {
      return;
    }
    executionStates.transition(current.getOrganizationId(), current.getId(), status, null);
  }

  private ExecuteAgentActionResponse reject(
      String toolCallId, UUID organizationId, ExecuteAgentActionRequest request, String reason) {
    // Logged at the server, not returned: a worker learns that it was refused, not the internal
    // reason, which could otherwise confirm the existence of another tenant's run or approval.
    log.warn(
        "Rejected agent action {} (runId={}, toolCallId={}): {}",
        request.actionType(),
        request.runId(),
        toolCallId,
        reason);

    if (organizationId != null) {
      auditEvents.save(
          new AuditEvent(
              organizationId,
              AuditActorType.AGENT,
              request.runId(),
              "agent.action.rejected",
              "agent_run",
              request.runId(),
              writeMetadata(
                  Map.of(
                      "toolCallId", toolCallId,
                      "actionType", request.actionType().name(),
                      "reason", reason))));
    }
    return ExecuteAgentActionResponse.rejected(toolCallId);
  }

  private void recordAudit(
      UUID organizationId,
      AgentRun run,
      ExecuteAgentActionRequest request,
      String eventType,
      Map<String, String> metadata) {
    auditEvents.save(
        new AuditEvent(
            organizationId,
            // The actor is the run, not a person: an AI-authored change stays distinguishable
            // from a human one forever after, not merely while someone remembers.
            AuditActorType.AGENT,
            run.getId(),
            eventType,
            "ticket",
            run.getTicketId(),
            writeMetadata(metadata)));
  }

  private String writeMetadata(Map<String, String> metadata) {
    // Identifiers and codes only. docs/SECURITY_AND_TESTING.md asks for sanitized summaries, and
    // an audit row carrying a published reply's text would duplicate tenant content into a table
    // with a different retention story.
    return objectMapper.writeValueAsString(metadata);
  }
}
