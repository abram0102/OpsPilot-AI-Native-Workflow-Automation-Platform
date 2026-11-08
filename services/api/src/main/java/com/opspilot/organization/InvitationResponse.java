package com.opspilot.organization;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for {@code POST /api/v1/organizations/{orgId}/invitations}. {@code token} is the
 * raw, unhashed secret — returned exactly once, here, to a caller who has already passed the
 * owner/admin check for this organization (the "protected preview" docs/TASKS.md T09 asks for;
 * there is no email delivery in V1). It is never persisted or logged anywhere (see {@link
 * InvitationTokens}).
 */
public record InvitationResponse(
    UUID id, String email, MembershipRole role, String token, Instant expiresAt) {}
