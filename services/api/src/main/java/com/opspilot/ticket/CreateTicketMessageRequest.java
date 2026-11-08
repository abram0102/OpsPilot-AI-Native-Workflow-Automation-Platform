package com.opspilot.ticket;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/organizations/{orgId}/tickets/{ticketId}/messages}. Always
 * creates a human-authored message (docs/API_CONTRACT.md: "Human-authored internal message only") —
 * an agent-authored message is created elsewhere, once the agent pipeline exists (T17+).
 */
public record CreateTicketMessageRequest(@NotBlank String body) {}
