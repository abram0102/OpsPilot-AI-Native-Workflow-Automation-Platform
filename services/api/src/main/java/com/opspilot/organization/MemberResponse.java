package com.opspilot.organization;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code GET /api/v1/organizations/{orgId}/members} and the result of {@code PATCH
 * .../members/{memberId}} (docs/API_CONTRACT.md). {@code membershipId} (not {@code userId}) is the
 * identifier {@code {memberId}} refers to.
 */
public record MemberResponse(
    UUID membershipId,
    UUID userId,
    String email,
    String displayName,
    MembershipRole role,
    Instant memberSince) {}
