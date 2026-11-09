package com.opspilot.agent;

/**
 * Tool execution lifecycle (docs/DATA_MODEL.md section C): {@code PROPOSED -> AUTHORIZED ->
 * EXECUTING -> SUCCEEDED}, with {@code EXECUTING} also able to fail retryably or permanently.
 *
 * <p>The split between the two failures is what a retry policy reads: a retryable failure may be
 * attempted again under the same {@code (run_id, tool_call_id)}, a permanent one may not.
 */
public enum ToolExecutionStatus {
  PROPOSED,
  AUTHORIZED,
  EXECUTING,
  SUCCEEDED,
  FAILED_RETRYABLE,
  FAILED_PERMANENT;

  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED_PERMANENT;
  }
}
