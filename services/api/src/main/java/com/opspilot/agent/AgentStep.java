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
import org.hibernate.type.SqlTypes;

/**
 * One recorded step of a run (docs/DATA_MODEL.md section A).
 *
 * <p>The {@code sanitized} prefixes are a contract, not a naming style: the data model requires "no
 * raw secrets" in these columns and docs/SECURITY_AND_TESTING.md asks for sanitized summaries
 * rather than raw payloads. Whatever writes a step is responsible for having redacted it first.
 */
@Entity
@Table(name = "agent_steps")
public class AgentStep {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "run_id", nullable = false)
  private UUID runId;

  @Column(name = "sequence", nullable = false)
  private int sequence;

  @Enumerated(EnumType.STRING)
  @Column(name = "step_type", nullable = false)
  private AgentStepType stepType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AgentStepStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "sanitized_input", columnDefinition = "jsonb")
  private String sanitizedInput;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "sanitized_output", columnDefinition = "jsonb")
  private String sanitizedOutput;

  @Column(name = "duration_ms")
  private Integer durationMs;

  @Column private String model;

  @Column(name = "tokens_in")
  private Integer tokensIn;

  @Column(name = "tokens_out")
  private Integer tokensOut;

  @Column(name = "error_code")
  private String errorCode;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AgentStep() {}

  public AgentStep(
      UUID organizationId,
      UUID runId,
      int sequence,
      AgentStepType stepType,
      AgentStepStatus status) {
    this.organizationId = organizationId;
    this.runId = runId;
    this.sequence = sequence;
    this.stepType = stepType;
    this.status = status;
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

  public int getSequence() {
    return sequence;
  }

  public AgentStepType getStepType() {
    return stepType;
  }

  public AgentStepStatus getStatus() {
    return status;
  }

  public String getSanitizedInput() {
    return sanitizedInput;
  }

  public String getSanitizedOutput() {
    return sanitizedOutput;
  }

  public Integer getDurationMs() {
    return durationMs;
  }

  public String getModel() {
    return model;
  }

  public Integer getTokensIn() {
    return tokensIn;
  }

  public Integer getTokensOut() {
    return tokensOut;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  void applyOutcome(
      AgentStepStatus status, String sanitizedOutput, Integer durationMs, String errorCode) {
    this.status = status;
    this.sanitizedOutput = sanitizedOutput;
    this.durationMs = durationMs;
    this.errorCode = errorCode;
  }

  void applyInput(String sanitizedInput) {
    this.sanitizedInput = sanitizedInput;
  }

  void applyModelUsage(String model, Integer tokensIn, Integer tokensOut) {
    this.model = model;
    this.tokensIn = tokensIn;
    this.tokensOut = tokensOut;
  }
}
