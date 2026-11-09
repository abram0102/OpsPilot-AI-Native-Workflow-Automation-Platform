package com.opspilot.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * A human decision gate in front of one proposed agent action (docs/DATA_MODEL.md section A).
 *
 * <p>{@code proposal} is the exact payload a reviewer is shown and is immutable once written: the
 * attack this prevents is a different body being substituted between proposal and execution, so
 * there is no setter for it anywhere. A decision is terminal — {@link #decide} refuses to move an
 * already-decided approval, which is what stops a second approval executing a tool twice.
 */
@Entity
@Table(name = "approvals")
public class Approval {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "run_id", nullable = false)
  private UUID runId;

  @Column(name = "ticket_id", nullable = false)
  private UUID ticketId;

  @Column(name = "tool_call_id", nullable = false)
  private String toolCallId;

  @Enumerated(EnumType.STRING)
  @Column(name = "action_type", nullable = false)
  private AgentActionType actionType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
  private String proposal;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ApprovalStatus status = ApprovalStatus.PENDING;

  @Column(name = "decision_by")
  private UUID decisionBy;

  @Column(name = "decision_at")
  private Instant decisionAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Approval() {}

  public Approval(
      UUID organizationId,
      UUID runId,
      UUID ticketId,
      String toolCallId,
      AgentActionType actionType,
      String proposal) {
    this.organizationId = organizationId;
    this.runId = runId;
    this.ticketId = ticketId;
    this.toolCallId = toolCallId;
    this.actionType = actionType;
    this.proposal = proposal;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getRunId() {
    return runId;
  }

  public UUID getTicketId() {
    return ticketId;
  }

  public String getToolCallId() {
    return toolCallId;
  }

  public AgentActionType getActionType() {
    return actionType;
  }

  public String getProposal() {
    return proposal;
  }

  public ApprovalStatus getStatus() {
    return status;
  }

  public UUID getDecisionBy() {
    return decisionBy;
  }

  public Instant getDecisionAt() {
    return decisionAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  void decide(ApprovalStatus outcome, UUID decidedBy, Instant decidedAt) {
    this.status = outcome;
    this.decisionBy = decidedBy;
    this.decisionAt = decidedAt;
  }

  void expire(Instant at) {
    this.status = ApprovalStatus.EXPIRED;
    this.decisionAt = at;
  }
}
