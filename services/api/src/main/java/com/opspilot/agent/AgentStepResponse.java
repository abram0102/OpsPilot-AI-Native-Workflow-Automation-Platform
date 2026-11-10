package com.opspilot.agent;

import java.time.Instant;
import java.util.UUID;

/**
 * One step of a run's trace.
 *
 * <p>Carries the {@code sanitized} columns as stored — whatever wrote the step was responsible for
 * redacting them (docs/DATA_MODEL.md: "no raw secrets"). This response adds no redaction of its
 * own, because redaction at read time would imply the stored row was unsafe.
 */
public record AgentStepResponse(
    UUID id,
    int sequence,
    AgentStepType stepType,
    AgentStepStatus status,
    String sanitizedInput,
    String sanitizedOutput,
    Integer durationMs,
    String model,
    Integer tokensIn,
    Integer tokensOut,
    String errorCode,
    Instant createdAt) {

  static AgentStepResponse from(AgentStep step) {
    return new AgentStepResponse(
        step.getId(),
        step.getSequence(),
        step.getStepType(),
        step.getStatus(),
        step.getSanitizedInput(),
        step.getSanitizedOutput(),
        step.getDurationMs(),
        step.getModel(),
        step.getTokensIn(),
        step.getTokensOut(),
        step.getErrorCode(),
        step.getCreatedAt());
  }
}
