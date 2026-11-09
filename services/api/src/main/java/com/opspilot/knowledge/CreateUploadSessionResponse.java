package com.opspilot.knowledge;

import java.util.UUID;

/** Response body for {@code POST /api/v1/organizations/{orgId}/knowledge/uploads}. */
public record CreateUploadSessionResponse(
    UUID documentId, String objectKey, String uploadUrl, String uploadMethod) {}
