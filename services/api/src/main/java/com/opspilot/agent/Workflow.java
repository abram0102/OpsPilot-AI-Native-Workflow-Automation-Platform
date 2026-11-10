package com.opspilot.agent;

/**
 * The workflows a run may execute.
 *
 * <p>An enum rather than a free string so an unrecognised value is a 400 at the edge instead of a
 * row nothing knows how to execute. docs/API_CONTRACT.md names exactly one today ({@code
 * SUPPORT_TRIAGE_V1}); the versioned suffix is the contract's, and a changed graph should arrive as
 * a new value rather than a silent redefinition of this one.
 */
public enum Workflow {
  SUPPORT_TRIAGE_V1
}
