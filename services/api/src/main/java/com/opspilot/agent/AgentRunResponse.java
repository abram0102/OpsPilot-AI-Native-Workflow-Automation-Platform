package com.opspilot.agent;

import java.time.Instant;
import java.util.UUID;

/** A run as the explorer lists it. */
public record AgentRunResponse(
    UUID id,
    UUID ticketId,
    Workflow workflow,
    AgentRunStatus status,
    int attemptCount,
    int stepsUsed,
    String lastErrorCode,
    Instant createdAt,
    Instant updatedAt) {

  static AgentRunResponse from(AgentRun run) {
    return new AgentRunResponse(
        run.getId(),
        run.getTicketId(),
        run.getWorkflow(),
        run.getStatus(),
        run.getAttemptCount(),
        run.getStepsUsed(),
        run.getLastErrorCode(),
        run.getCreatedAt(),
        run.getUpdatedAt());
  }
}
