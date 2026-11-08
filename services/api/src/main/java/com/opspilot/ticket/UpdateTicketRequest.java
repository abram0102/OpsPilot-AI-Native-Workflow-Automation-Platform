package com.opspilot.ticket;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for {@code PATCH /api/v1/organizations/{orgId}/tickets/{ticketId}}
 * (docs/API_CONTRACT.md: "Update status, priority, assigneeId with optimistic version"). {@code
 * version} is the version the caller last saw (from a prior GET) and is required — it is what makes
 * "stale version -> 409" an explicit, deterministic check rather than relying only on a true race
 * between two in-flight requests. {@code status}/{@code priority}/{@code assigneeId} are each
 * applied only when present; omitting a field leaves it unchanged.
 */
public record UpdateTicketRequest(
    @NotNull Long version, TicketStatus status, TicketPriority priority, UUID assigneeId) {}
