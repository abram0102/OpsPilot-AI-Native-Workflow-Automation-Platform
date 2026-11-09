package com.opspilot.agent;

import com.opspilot.common.web.BadRequestException;
import com.opspilot.common.web.ConflictException;
import com.opspilot.common.web.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The approval gate (docs/DATA_MODEL.md section C; docs/TASKS.md T17).
 *
 * <p>Three AGENTS.md invariants live here: a pending approval cannot be executed, a rejected one
 * cannot be executed, and a double approval cannot execute a tool twice. The last is handled by
 * returning the existing outcome rather than erroring — see ADR-041 for why that choice over a 409,
 * which the data model leaves to the implementer.
 *
 * <p>No HTTP surface: the approval endpoints are T22's. This is the persistence layer they build
 * on.
 */
@Service
public class ApprovalService {

  private final ApprovalRepository approvals;

  public ApprovalService(ApprovalRepository approvals) {
    this.approvals = approvals;
  }

  /**
   * Records a proposal awaiting a human.
   *
   * <p>Idempotent on {@code (runId, toolCallId)}: a redelivered proposal returns the existing row
   * rather than creating a second gate for the same call. The unique constraint is the real
   * guarantee — this check only avoids relying on a constraint violation for normal flow.
   */
  @Transactional
  public Approval propose(
      UUID organizationId,
      UUID runId,
      UUID ticketId,
      String toolCallId,
      AgentActionType actionType,
      String proposalJson) {
    if (proposalJson == null || proposalJson.isBlank()) {
      throw new BadRequestException("An approval must carry the exact proposal being approved.");
    }

    Optional<Approval> existing = approvals.findByRunIdAndToolCallId(runId, toolCallId);
    if (existing.isPresent()) {
      return existing.get();
    }

    return approvals.saveAndFlush(
        new Approval(organizationId, runId, ticketId, toolCallId, actionType, proposalJson));
  }

  @Transactional(readOnly = true)
  public Approval require(UUID organizationId, UUID approvalId) {
    return approvals
        .findByIdAndOrganizationId(approvalId, organizationId)
        .orElseThrow(() -> new NotFoundException("No such approval."));
  }

  /**
   * Records a human decision.
   *
   * <p>Deciding an already-decided approval the *same* way returns the stored outcome unchanged —
   * at-least-once delivery and double-clicked buttons both produce that, and neither should be an
   * error or a second effect. Deciding it a *different* way is a real conflict and is refused: the
   * first decision is the one that stands.
   */
  @Transactional
  public Approval decide(
      UUID organizationId, UUID approvalId, ApprovalStatus outcome, UUID decidedBy) {
    if (outcome != ApprovalStatus.APPROVED && outcome != ApprovalStatus.REJECTED) {
      throw new BadRequestException("A decision must be APPROVED or REJECTED.");
    }

    Approval approval =
        approvals
            .findByIdAndOrganizationIdForUpdate(approvalId, organizationId)
            .orElseThrow(() -> new NotFoundException("No such approval."));

    if (approval.getStatus() == outcome) {
      return approval;
    }
    if (approval.getStatus().isDecided()) {
      throw new ConflictException(
          "This approval is already " + approval.getStatus() + " and cannot be changed.");
    }

    approval.decide(outcome, decidedBy, Instant.now());
    return approvals.saveAndFlush(approval);
  }

  /** Lets a pending approval lapse. Never touches one a human already decided. */
  @Transactional
  public Approval expire(UUID organizationId, UUID approvalId) {
    Approval approval = require(organizationId, approvalId);

    if (approval.getStatus().isDecided()) {
      throw new ConflictException(
          "This approval is already " + approval.getStatus() + " and cannot expire.");
    }
    approval.expire(Instant.now());
    return approvals.saveAndFlush(approval);
  }

  /**
   * Whether this approval authorises execution right now.
   *
   * <p>The one question the executor asks before acting, and the reason it is a method here rather
   * than a status comparison at each call site.
   */
  @Transactional(readOnly = true)
  public boolean authorizesExecution(UUID organizationId, UUID approvalId) {
    return require(organizationId, approvalId).getStatus() == ApprovalStatus.APPROVED;
  }

  @Transactional(readOnly = true)
  public List<Approval> listPending(UUID organizationId) {
    return approvals.findAllByOrganizationIdAndStatusOrderByCreatedAt(
        organizationId, ApprovalStatus.PENDING);
  }
}
