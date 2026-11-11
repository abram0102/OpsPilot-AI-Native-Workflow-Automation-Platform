package com.opspilot.common.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * The message shape on {@code opspilot:events} (docs/API_CONTRACT.md "Redis Stream contracts").
 *
 * <p>Identifiers only, deliberately: "Published event payload only contains identifiers; workers
 * reload trusted DB state." Nothing here carries ticket text, a drafted reply or any tenant
 * content, so a stream someone can read is not a disclosure of what the agent said.
 *
 * <p>{@code eventId} is the outbox row's own id rather than a fresh uuid. That is what makes
 * consumer de-duplication possible at all — a republished row carries the same id, so a consumer
 * can recognise the repeat (docs/API_CONTRACT.md: "consumers de-duplicate via `eventId`").
 */
public record OutboxEventEnvelope(
    UUID eventId,
    String type,
    UUID aggregateId,
    UUID organizationId,
    int schemaVersion,
    Instant createdAt) {

  /**
   * The envelope version this service writes and understands.
   *
   * <p>Defined here because it was previously defined nowhere — docs/API_CONTRACT.md shows {@code
   * "schemaVersion": 1} in an example, which is a sample rather than a constant. A consumer seeing
   * anything else is looking at a message from a different deployment generation.
   */
  public static final int SCHEMA_VERSION = 1;

  static OutboxEventEnvelope of(OutboxEvent event) {
    return new OutboxEventEnvelope(
        event.getId(),
        event.getEventType(),
        event.getAggregateId(),
        event.getOrganizationId(),
        SCHEMA_VERSION,
        event.getCreatedAt());
  }
}
