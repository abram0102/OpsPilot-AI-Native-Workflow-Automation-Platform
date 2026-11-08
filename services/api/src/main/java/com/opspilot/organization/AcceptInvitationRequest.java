package com.opspilot.organization;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/v1/invitations/accept}. */
public record AcceptInvitationRequest(@NotBlank String token) {}
