package com.opspilot.agent;

import java.time.Instant;
import java.util.UUID;

/**
 * A single run: "State, timings, sanitized summary, usage" (docs/API_CONTRACT.md).
 *
 * <p>Token totals are what a provider actually reported. No cost figure is exposed here — pricing
 * belongs to {@code agent_usage} and is an estimate (T17/ADR-041), and a number labelled as spend
 * on a run detail page would read as billed.
 */
public record AgentRunDetailResponse(
    UUID id,
    UUID ticketId,
    Workflow workflow,
    AgentRunStatus status,
    UUID initiatedBy,
    int attemptCount,
    int stepsUsed,
    int totalInputTokens,
    int totalOutputTokens,
    String lastErrorCode,
    Instant createdAt,
    Instant updatedAt) {

  static AgentRunDetailResponse from(AgentRun run) {
    return new AgentRunDetailResponse(
        run.getId(),
        run.getTicketId(),
        run.getWorkflow(),
        run.getStatus(),
        run.getInitiatedBy(),
        run.getAttemptCount(),
        run.getStepsUsed(),
        run.getTotalInputTokens(),
        run.getTotalOutputTokens(),
        run.getLastErrorCode(),
        run.getCreatedAt(),
        run.getUpdatedAt());
  }
}
