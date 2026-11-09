package com.opspilot.knowledge;

import java.time.Instant;
import java.util.UUID;

/** Response body shape for knowledge document metadata (list items and detail). */
public record KnowledgeDocumentResponse(
    UUID id,
    String filename,
    String mimeType,
    long sizeBytes,
    KnowledgeDocumentStatus status,
    String errorCode,
    Instant createdAt,
    Instant updatedAt) {

  static KnowledgeDocumentResponse from(KnowledgeDocument document) {
    return new KnowledgeDocumentResponse(
        document.getId(),
        document.getFilename(),
        document.getMimeType(),
        document.getSizeBytes(),
        document.getStatus(),
        document.getErrorCode(),
        document.getCreatedAt(),
        document.getUpdatedAt());
  }
}
