package com.opspilot.agent;

/**
 * Approval lifecycle (docs/DATA_MODEL.md section C): {@code PENDING -> APPROVED | REJECTED |
 * EXPIRED}, and every outcome is "terminal and immutable".
 */
public enum ApprovalStatus {
  PENDING,
  APPROVED,
  REJECTED,
  EXPIRED;

  public boolean isDecided() {
    return this != PENDING;
  }
}
