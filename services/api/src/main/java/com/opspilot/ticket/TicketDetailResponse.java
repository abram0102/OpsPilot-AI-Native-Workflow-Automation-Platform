package com.opspilot.ticket;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response body for {@code GET /api/v1/organizations/{orgId}/tickets/{ticketId}}
 * (docs/API_CONTRACT.md: "Detail with tags/messages summary"). The full message conversation has
 * its own endpoint ({@code GET .../tickets/{ticketId}/messages}); {@code messageCount} is the
 * "summary" this detail view adds, not the full thread.
 */
public record TicketDetailResponse(
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
    Instant updatedAt,
    List<String> tags,
    long messageCount) {

  static TicketDetailResponse from(Ticket ticket, List<String> tags, long messageCount) {
    return new TicketDetailResponse(
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
        ticket.getUpdatedAt(),
        tags,
        messageCount);
  }
}
