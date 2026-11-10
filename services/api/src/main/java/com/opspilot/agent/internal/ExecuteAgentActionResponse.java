package com.opspilot.agent.internal;

/**
 * docs/API_CONTRACT.md's canonical reply: {@code {toolCallId, status, resultReference,
 * alreadyApplied}}.
 *
 * <p>Only three statuses, deliberately fewer than {@code ToolExecutionStatus}'s six: a worker needs
 * to know whether to stop, retry, or treat the call as done, and the internal state machine's finer
 * distinctions are ours rather than its business.
 */
public record ExecuteAgentActionResponse(
    String toolCallId, ActionStatus status, String resultReference, boolean alreadyApplied) {

  public enum ActionStatus {
    SUCCEEDED,
    /** Policy, approval or tenant checks refused it. Retrying unchanged will refuse again. */
    REJECTED,
    /** Something went wrong that a retry might survive. */
    FAILED
  }

  static ExecuteAgentActionResponse succeeded(
      String toolCallId, String resultReference, boolean alreadyApplied) {
    return new ExecuteAgentActionResponse(
        toolCallId, ActionStatus.SUCCEEDED, resultReference, alreadyApplied);
  }

  static ExecuteAgentActionResponse rejected(String toolCallId) {
    return new ExecuteAgentActionResponse(toolCallId, ActionStatus.REJECTED, null, false);
  }
}
