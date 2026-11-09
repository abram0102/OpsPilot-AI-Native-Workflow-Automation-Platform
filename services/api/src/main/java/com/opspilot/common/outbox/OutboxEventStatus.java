package com.opspilot.common.outbox;

/** docs/DATA_MODEL.md: {@code PENDING -> PUBLISHED / DEAD}. */
public enum OutboxEventStatus {
  PENDING,
  PUBLISHED,
  DEAD
}
