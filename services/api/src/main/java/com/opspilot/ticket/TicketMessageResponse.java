package com.opspilot.ticket;

import java.time.Instant;
import java.util.UUID;

/** Response body shape for a single ticket message. */
public record TicketMessageResponse(
    UUID id,
    TicketMessageAuthorType authorType,
    UUID authorId,
    String body,
    UUID publishedBy,
    UUID sourceRunId,
    Instant createdAt) {

  static TicketMessageResponse from(TicketMessage message) {
    return new TicketMessageResponse(
        message.getId(),
        message.getAuthorType(),
        message.getAuthorId(),
        message.getBody(),
        message.getPublishedBy(),
        message.getSourceRunId(),
        message.getCreatedAt());
  }
}
