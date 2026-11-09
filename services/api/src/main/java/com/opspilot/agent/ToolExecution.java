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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The record of one tool call's effect (docs/DATA_MODEL.md section A).
 *
 * <p>Unique on {@code (run_id, tool_call_id)}, which is the whole point: at-least-once delivery
 * means the same call will arrive twice, and the constraint is what makes the second arrival return
 * the first outcome instead of acting again. {@code result_ref} points at what the effect produced
 * rather than copying it.
 */
@Entity
@Table(name = "tool_executions")
public class ToolExecution {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "run_id", nullable = false)
  private UUID runId;

  @Column(name = "tool_call_id", nullable = false)
  private String toolCallId;

  @Enumerated(EnumType.STRING)
  @Column(name = "action_type", nullable = false)
  private AgentActionType actionType;

  @Column(name = "approval_id")
  private UUID approvalId;

  @Column(name = "result_ref")
  private String resultRef;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ToolExecutionStatus status = ToolExecutionStatus.PROPOSED;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ToolExecution() {}

  public ToolExecution(
      UUID organizationId, UUID runId, String toolCallId, AgentActionType actionType) {
    this.organizationId = organizationId;
    this.runId = runId;
    this.toolCallId = toolCallId;
    this.actionType = actionType;
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

  public String getToolCallId() {
    return toolCallId;
  }

  public AgentActionType getActionType() {
    return actionType;
  }

  public UUID getApprovalId() {
    return approvalId;
  }

  public String getResultRef() {
    return resultRef;
  }

  public ToolExecutionStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  void applyStatus(ToolExecutionStatus next, String resultRef) {
    this.status = next;
    if (resultRef != null) {
      this.resultRef = resultRef;
    }
  }

  void linkApproval(UUID approvalId) {
    this.approvalId = approvalId;
  }
}
