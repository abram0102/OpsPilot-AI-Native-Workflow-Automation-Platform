package com.opspilot.organization;

import java.util.UUID;

/** Response body for {@code POST /api/v1/invitations/accept}. */
public record AcceptInvitationResponse(
    UUID organizationId, String organizationName, MembershipRole role) {}
