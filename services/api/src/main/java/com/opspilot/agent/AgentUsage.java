package com.opspilot.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Per-run token usage (docs/DATA_MODEL.md section A).
 *
 * <p>Token counts are what a provider actually reported; {@code estimatedCostUsd} is derived from
 * them and named so nobody mistakes it for a billed amount. The data model asks for "numerical
 * actual tokens vs estimated spend clearly labeled", and a nullable cost is honest about the case
 * where no price is known for a model.
 */
@Entity
@Table(name = "agent_usage")
public class AgentUsage {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "run_id", nullable = false)
  private UUID runId;

  @Column(nullable = false)
  private String model;

  @Column(name = "input_tokens", nullable = false)
  private int inputTokens;

  @Column(name = "output_tokens", nullable = false)
  private int outputTokens;

  @Column(name = "estimated_cost_usd")
  private BigDecimal estimatedCostUsd;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AgentUsage() {}

  public AgentUsage(
      UUID organizationId,
      UUID runId,
      String model,
      int inputTokens,
      int outputTokens,
      BigDecimal estimatedCostUsd) {
    this.organizationId = organizationId;
    this.runId = runId;
    this.model = model;
    this.inputTokens = inputTokens;
    this.outputTokens = outputTokens;
    this.estimatedCostUsd = estimatedCostUsd;
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

  public String getModel() {
    return model;
  }

  public int getInputTokens() {
    return inputTokens;
  }

  public int getOutputTokens() {
    return outputTokens;
  }

  public BigDecimal getEstimatedCostUsd() {
    return estimatedCostUsd;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
