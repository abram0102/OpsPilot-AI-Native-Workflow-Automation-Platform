package com.opspilot.agent;

/**
 * Run lifecycle (docs/DATA_MODEL.md section C).
 *
 * <p>{@code AWAITING_APPROVAL} is the state that makes this product's central promise structural
 * rather than procedural: a run that proposes a user-visible action stops here until a human
 * decides, and cannot reach {@code COMPLETED} on its own.
 */
public enum AgentRunStatus {
  QUEUED,
  RUNNING,
  AWAITING_APPROVAL,
  RETRY_SCHEDULED,
  COMPLETED,
  FAILED,
  CANCELLED;

  /** Terminal states never transition again; nothing may reopen a finished run. */
  public boolean isTerminal() {
    return this == COMPLETED || this == FAILED || this == CANCELLED;
  }
}
