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
 * One execution of the agent against one ticket (docs/DATA_MODEL.md section A).
 *
 * <p>Nothing creates these yet — the start-run API is T18 — but the row's rules live here so that
 * ticket inherits them. Status changes go through {@link AgentRunService}, which is the only place
 * the transition table is enforced; there is deliberately no public status setter.
 */
@Entity
@Table(name = "agent_runs")
public class AgentRun {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "ticket_id", nullable = false)
  private UUID ticketId;

  @Column(name = "initiated_by", nullable = false)
  private UUID initiatedBy;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AgentRunStatus status = AgentRunStatus.QUEUED;

  @Column(name = "checkpoint_thread_id")
  private UUID checkpointThreadId;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount = 0;

  @Column(name = "lease_owner")
  private String leaseOwner;

  @Column(name = "lease_expires_at")
  private Instant leaseExpiresAt;

  @Column(name = "steps_used", nullable = false)
  private int stepsUsed = 0;

  @Column(name = "total_input_tokens", nullable = false)
  private int totalInputTokens = 0;

  @Column(name = "total_output_tokens", nullable = false)
  private int totalOutputTokens = 0;

  @Column(name = "last_error_code")
  private String lastErrorCode;

  @Column(name = "idempotency_key")
  private String idempotencyKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Workflow workflow = Workflow.SUPPORT_TRIAGE_V1;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected AgentRun() {}

  public AgentRun(
      UUID organizationId,
      UUID ticketId,
      UUID initiatedBy,
      Workflow workflow,
      String idempotencyKey) {
    this.organizationId = organizationId;
    this.ticketId = ticketId;
    this.initiatedBy = initiatedBy;
    this.workflow = workflow;
    this.idempotencyKey = idempotencyKey;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getTicketId() {
    return ticketId;
  }

  public UUID getInitiatedBy() {
    return initiatedBy;
  }

  public AgentRunStatus getStatus() {
    return status;
  }

  public UUID getCheckpointThreadId() {
    return checkpointThreadId;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public String getLeaseOwner() {
    return leaseOwner;
  }

  public Instant getLeaseExpiresAt() {
    return leaseExpiresAt;
  }

  public int getStepsUsed() {
    return stepsUsed;
  }

  public int getTotalInputTokens() {
    return totalInputTokens;
  }

  public int getTotalOutputTokens() {
    return totalOutputTokens;
  }

  public String getLastErrorCode() {
    return lastErrorCode;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public Workflow getWorkflow() {
    return workflow;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Package-private: {@link AgentRunService} owns the transition table. */
  void applyStatus(AgentRunStatus next, String errorCode) {
    this.status = next;
    this.lastErrorCode = errorCode;
  }

  void applyLease(String owner, Instant expiresAt) {
    this.leaseOwner = owner;
    this.leaseExpiresAt = expiresAt;
  }

  void recordAttempt() {
    this.attemptCount += 1;
  }

  void recordUsage(int inputTokens, int outputTokens, int stepsUsed) {
    this.totalInputTokens += inputTokens;
    this.totalOutputTokens += outputTokens;
    this.stepsUsed = stepsUsed;
  }

  void assignCheckpointThread(UUID threadId) {
    this.checkpointThreadId = threadId;
  }

  /**
   * True while {@code at} falls inside an unexpired lease held by someone else. A run with no
   * lease, or whose lease has expired, is claimable — which is what makes a crashed worker
   * recoverable rather than a permanent stall.
   */
  public boolean isLeasedAt(Instant at) {
    return leaseExpiresAt != null && leaseExpiresAt.isAfter(at);
  }
}
