package com.opspilot.auth;

import com.opspilot.organization.MembershipRole;
import java.util.List;
import java.util.UUID;

/** Response body for {@code GET /api/v1/me} (docs/API_CONTRACT.md). */
public record MeResponse(
    UUID userId, String email, String displayName, List<OrganizationMembershipView> organizations) {

  public record OrganizationMembershipView(UUID organizationId, String name, MembershipRole role) {}
}
