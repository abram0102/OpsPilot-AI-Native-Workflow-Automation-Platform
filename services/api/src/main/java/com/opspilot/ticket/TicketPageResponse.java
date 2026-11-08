package com.opspilot.ticket;

import java.util.List;

/**
 * The list envelope docs/API_CONTRACT.md's "Common HTTP conventions" specify: {@code {"items":
 * [...], "nextCursor": "...", "total": null}}. {@code total} is intentionally not a field here —
 * this API never computes one ("avoid inaccurate total if not computed"), and the global Jackson
 * config ({@code spring.jackson.default-property-inclusion: non_null}) already omits any null
 * field, so a {@code total} field would serialize identically to simply not having one.
 */
public record TicketPageResponse(List<TicketResponse> items, String nextCursor) {}
