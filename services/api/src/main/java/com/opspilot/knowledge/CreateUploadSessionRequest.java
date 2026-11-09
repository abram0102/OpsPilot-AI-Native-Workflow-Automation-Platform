package com.opspilot.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Request body for {@code POST /api/v1/organizations/{orgId}/knowledge/uploads}
 * (docs/API_CONTRACT.md). {@code sizeBytes}/{@code sha256} are the caller's *declared* values —
 * {@link KnowledgeServiceImpl#completeUpload} verifies them against what was actually stored, never
 * trusting these alone.
 */
public record CreateUploadSessionRequest(
    @NotBlank String filename,
    @NotBlank String mimeType,
    @Positive long sizeBytes,
    @NotBlank
        @Pattern(
            regexp = "^[0-9a-f]{64}$",
            message = "must be a 64-character lowercase hex SHA-256")
        String sha256) {}
