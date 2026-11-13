package com.opspilot.agent;

import com.opspilot.audit.AuditActorType;
import com.opspilot.audit.AuditEvent;
import com.opspilot.audit.AuditEventRepository;
import com.opspilot.auth.AuthenticatedPrincipal;
import com.opspilot.auth.OrganizationAccessService;
import com.opspilot.auth.OrganizationAction;
import com.opspilot.common.outbox.OutboxEvent;
import com.opspilot.common.outbox.OutboxEventRepository;
import com.opspilot.common.outbox.OutboxEventType;
import com.opspilot.common.web.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The reviewer-facing approval API (docs/TASKS.md T22).
 *
 * <p>Separate from {@link ApprovalService}, which owns the approval state machine: that one is
 * called by the worker-facing paths and knows nothing about who is asking. This one adds the parts
 * specific to a human deciding — the role check, the audit row naming the actor, and the resume
 * event that tells the worker to carry on.
 *
 * <p>The decision and its outbox event commit together. docs/API_CONTRACT.md: "commits decision +
 * outbox in one transaction" — and the sentence after it matters just as much: "no side effect
 * occurs merely because approval HTTP returned 200". Nothing here publishes anything; it records a
 * decision and queues a resume, and T23's worker re-checks that decision before acting.
 */
@Service
public class ApprovalDecisionService {

  /** docs/API_CONTRACT.md's Redis envelope type for continuing a paused run. */
  private static final String RUN_RESUME_EVENT = OutboxEventType.AGENT_RUN_RESUME;

  private final ApprovalRepository approvals;
  private final ApprovalService approvalService;
  private final OutboxEventRepository outboxEvents;
  private final AuditEventRepository auditEvents;
  private final OrganizationAccessService accessService;
  private final ObjectMapper objectMapper;

  public ApprovalDecisionService(
      ApprovalRepository approvals,
      ApprovalService approvalService,
      OutboxEventRepository outboxEvents,
      AuditEventRepository auditEvents,
      OrganizationAccessService accessService,
      ObjectMapper objectMapper) {
    this.approvals = approvals;
    this.approvalService = approvalService;
    this.outboxEvents = outboxEvents;
    this.auditEvents = auditEvents;
    this.accessService = accessService;
    this.objectMapper = objectMapper;
  }

  /**
   * The inbox, and the run explorer's "what did this run propose" panel (T27).
   *
   * <p>{@code runId} deliberately changes the no-status default. The inbox answers "what is waiting
   * for me", so with no status it shows {@code PENDING}. A run's page answers "what did this run
   * propose and what happened to it", where an approval that was already approved or rejected is
   * the most interesting row on the page — defaulting that view to pending only would hide the
   * decision the run is waiting on having had.
   *
   * <p>Both branches scope on {@code organization_id} in the query itself, not after the fact: a
   * {@code runId} is a client-supplied value, and a run belonging to another tenant must come back
   * empty rather than be filtered in memory (AGENTS.md invariants).
   */
  @Transactional(readOnly = true)
  public List<ApprovalResponse> list(
      AuthenticatedPrincipal principal, UUID organizationId, ApprovalStatus status, UUID runId) {
    accessService.requireAction(principal, organizationId, OrganizationAction.DECIDE_APPROVAL);

    List<Approval> rows;
    if (runId != null) {
      rows = approvals.findAllByRunIdAndOrganizationIdOrderByCreatedAt(runId, organizationId);
      if (status != null) {
        rows = rows.stream().filter(approval -> approval.getStatus() == status).toList();
      }
    } else {
      rows =
          approvals.findAllByOrganizationIdAndStatusOrderByCreatedAt(
              organizationId, status == null ? ApprovalStatus.PENDING : status);
    }

    return rows.stream().map(this::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public ApprovalResponse get(
      AuthenticatedPrincipal principal, UUID organizationId, UUID approvalId) {
    accessService.requireAction(principal, organizationId, OrganizationAction.DECIDE_APPROVAL);
    return toResponse(
        approvals
            .findByIdAndOrganizationId(approvalId, organizationId)
            .orElseThrow(() -> new NotFoundException("No such approval.")));
  }

  /**
   * Records a human decision and queues the run's resume.
   *
   * <p>Takes no payload beyond the outcome: there is deliberately no parameter through which a
   * caller could supply reply text, because the proposal is immutable and the whole point of the
   * gate is that the approved text and the published text are the same string.
   */
  @Transactional
  public ApprovalResponse decide(
      AuthenticatedPrincipal principal,
      UUID organizationId,
      UUID approvalId,
      ApprovalStatus outcome) {
    accessService.requireAction(principal, organizationId, OrganizationAction.DECIDE_APPROVAL);

    // Locks the row for the duration, so two reviewers clicking at once serialize rather than
    // race; ApprovalService then refuses whichever arrives second with a different outcome, and
    // returns the stored outcome unchanged for a repeat of the same one.
    Approval before =
        approvals
            .findByIdAndOrganizationIdForUpdate(approvalId, organizationId)
            .orElseThrow(() -> new NotFoundException("No such approval."));
    boolean alreadyDecided = before.getStatus().isDecided();

    Approval approval =
        approvalService.decide(organizationId, approvalId, outcome, principal.userId());

    if (!alreadyDecided) {
      // Only a first decision resumes a run or writes an audit row. A repeated identical click is
      // idempotent all the way down — no second event, no second audit entry.
      recordAudit(organizationId, approval, principal);
      queueResume(organizationId, approval);
    }

    return toResponse(approval);
  }

  private void recordAudit(
      UUID organizationId, Approval approval, AuthenticatedPrincipal principal) {
    auditEvents.save(
        new AuditEvent(
            organizationId,
            // A human decided this one, unlike the agent-authored rows T21 writes.
            AuditActorType.USER,
            principal.userId(),
            "approval." + approval.getStatus().name().toLowerCase(java.util.Locale.ROOT),
            "approval",
            approval.getId(),
            objectMapper.writeValueAsString(
                Map.of(
                    "runId", approval.getRunId().toString(),
                    "toolCallId", approval.getToolCallId(),
                    "actionType", approval.getActionType().name()))));
  }

  private void queueResume(UUID organizationId, Approval approval) {
    // Identifiers only; the worker reloads trusted state, including re-reading the decision it is
    // being told about (docs/API_CONTRACT.md).
    String payload =
        objectMapper.writeValueAsString(
            Map.of(
                "runId", approval.getRunId().toString(),
                "organizationId", organizationId.toString(),
                "approvalId", approval.getId().toString()));

    outboxEvents.save(
        new OutboxEvent(
            RUN_RESUME_EVENT, "agent_run", approval.getRunId(), organizationId, payload));
  }

  private ApprovalResponse toResponse(Approval approval) {
    JsonNode proposal = objectMapper.readTree(approval.getProposal());

    JsonNode bodyNode = proposal.get("body");
    String body = bodyNode == null ? null : bodyNode.stringValue();

    return new ApprovalResponse(
        approval.getId(),
        approval.getRunId(),
        approval.getTicketId(),
        approval.getToolCallId(),
        approval.getActionType(),
        approval.getStatus(),
        new ApprovalResponse.ProposalView(body),
        readEvidence(proposal),
        approval.getDecisionBy(),
        approval.getDecisionAt(),
        approval.getCreatedAt(),
        approval.getUpdatedAt());
  }

  /**
   * Reads the citations out of the stored proposal.
   *
   * <p><b>The stored keys are snake_case; the response's are camelCase.</b> The proposal JSON is
   * written by the Python worker ({@code graphs/triage.py:_approval_proposal}) and carries {@code
   * document_id} / {@code chunk_index}, matching {@code RetrievedChunk} in docs/API_CONTRACT.md.
   * The HTTP shape this method builds is camelCase, as the same document's approval-response
   * example specifies. This reader is the boundary between the two.
   *
   * <p>It read camelCase until T35, which means every real citation was silently dropped and every
   * reviewer was told "No sources were cited" about a draft that did cite sources — the worst
   * direction for that particular error to point. It passed CI because the test fabricated a
   * camelCase payload rather than the one the worker writes; the test now uses the real shape.
   *
   * <p>Tolerant of absence: an approval carrying no citations simply has no evidence to show, and
   * an empty list is the honest rendering of that rather than a reason to fail the request.
   */
  private List<ApprovalResponse.EvidenceView> readEvidence(JsonNode proposal) {
    JsonNode citations = proposal.get("citations");
    if (citations == null || !citations.isArray()) {
      return List.of();
    }

    List<ApprovalResponse.EvidenceView> evidence = new ArrayList<>();
    for (JsonNode citation : citations) {
      JsonNode documentId = citation.get("document_id");
      JsonNode chunkIndex = citation.get("chunk_index");
      JsonNode title = citation.get("title");
      if (documentId == null || documentId.stringValue() == null) {
        continue;
      }
      try {
        evidence.add(
            new ApprovalResponse.EvidenceView(
                UUID.fromString(documentId.stringValue()),
                chunkIndex == null ? null : chunkIndex.asInt(),
                title == null ? null : title.stringValue()));
      } catch (IllegalArgumentException e) {
        // A malformed citation is dropped rather than failing the whole review: a reviewer seeing
        // one fewer source is recoverable, a reviewer seeing nothing at all is not.
        continue;
      }
    }
    return List.copyOf(evidence);
  }
}
