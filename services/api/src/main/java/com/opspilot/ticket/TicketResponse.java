package com.opspilot.ticket;

import java.time.Instant;
import java.util.UUID;

/** Response body shape shared by ticket creation, detail (nested) and list items. */
public record TicketResponse(
    UUID id,
    UUID organizationId,
    UUID customerId,
    String subject,
    String description,
    TicketStatus status,
    TicketPriority priority,
    UUID assigneeId,
    UUID createdBy,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  static TicketResponse from(Ticket ticket) {
    return new TicketResponse(
        ticket.getId(),
        ticket.getOrganizationId(),
        ticket.getCustomerId(),
        ticket.getSubject(),
        ticket.getDescription(),
        ticket.getStatus(),
        ticket.getPriority(),
        ticket.getAssigneeId(),
        ticket.getCreatedBy(),
        ticket.getVersion(),
        ticket.getCreatedAt(),
        ticket.getUpdatedAt());
  }
}
