package com.opspilot.agent;

/** Per-step outcome. Also defined by this ticket rather than the data model (ADR-041). */
public enum AgentStepStatus {
  STARTED,
  SUCCEEDED,
  FAILED,
  SKIPPED
}
