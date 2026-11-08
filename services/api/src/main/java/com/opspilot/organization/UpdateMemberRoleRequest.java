package com.opspilot.organization;

import jakarta.validation.constraints.NotNull;

/** Request body for {@code PATCH /api/v1/organizations/{orgId}/members/{memberId}}. */
public record UpdateMemberRoleRequest(@NotNull MembershipRole role) {}
