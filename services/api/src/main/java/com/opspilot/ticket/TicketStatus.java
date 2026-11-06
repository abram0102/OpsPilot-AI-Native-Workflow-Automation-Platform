package com.opspilot.ticket;

/**
 * docs/DATA_MODEL.md section C: OPEN -> IN_PROGRESS -> RESOLVED -> CLOSED, RESOLVED -> IN_PROGRESS
 * allowed.
 */
public enum TicketStatus {
  OPEN,
  IN_PROGRESS,
  RESOLVED,
  CLOSED
}
