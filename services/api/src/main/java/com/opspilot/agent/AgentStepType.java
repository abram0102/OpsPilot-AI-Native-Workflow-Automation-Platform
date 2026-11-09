package com.opspilot.agent;

/**
 * The kinds of step a run records.
 *
 * <p>docs/DATA_MODEL.md names the {@code step_type} column but does not enumerate its values, so
 * these are defined by this ticket (docs/DECISIONS.md ADR-041) to match the graph T19 will walk:
 * classify the ticket, retrieve knowledge, draft a reply, propose a tool call, wait for a human,
 * execute what was approved, finish.
 */
public enum AgentStepType {
  CLASSIFY,
  RETRIEVE,
  DRAFT_REPLY,
  PROPOSE_TOOL,
  AWAIT_APPROVAL,
  EXECUTE_TOOL,
  FINALIZE
}
