package com.opspilot.organization;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for {@code POST /api/v1/organizations} and {@code GET
 * /api/v1/organizations/{orgId}} (docs/API_CONTRACT.md). {@code myRole} is always the caller's own
 * resolved membership role, never a client-supplied value.
 */
public record OrganizationResponse(
    UUID id, String name, String slug, Instant createdAt, MembershipRole myRole) {}
