package com.opspilot.audit;

/**
 * Who performed an audited action. An agent run is a first-class actor, never disguised as a user.
 */
public enum AuditActorType {
  USER,
  AGENT,
  SYSTEM
}
