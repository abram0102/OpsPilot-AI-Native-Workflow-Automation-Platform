package com.opspilot.organization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request body for {@code POST /api/v1/organizations} (docs/API_CONTRACT.md). */
public record CreateOrganizationRequest(
    @NotBlank @Size(max = 200) String name,
    @NotBlank
        @Size(max = 63)
        @Pattern(
            regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
            message =
                "must be lowercase letters, numbers and hyphens only, no leading/trailing hyphen")
        String slug) {}
