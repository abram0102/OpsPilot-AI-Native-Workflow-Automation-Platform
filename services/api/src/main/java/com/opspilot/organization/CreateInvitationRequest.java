package com.opspilot.organization;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request body for {@code POST /api/v1/organizations/{orgId}/invitations}. */
public record CreateInvitationRequest(
    @NotBlank @Email String email, @NotNull MembershipRole role) {}
