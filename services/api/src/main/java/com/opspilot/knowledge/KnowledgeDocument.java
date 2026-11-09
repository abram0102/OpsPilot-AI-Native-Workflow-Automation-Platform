package com.opspilot.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Uploaded knowledge document metadata (docs/TASKS.md T13). {@code filename} is cosmetic only —
 * {@code objectKey} (server-generated, see {@link com.opspilot.storage.StorageService}) is what
 * actually locates the private object, never the client-supplied filename
 * (docs/SECURITY_AND_TESTING.md: "S3 key not derived from filename").
 */
@Entity
@Table(name = "knowledge_documents")
public class KnowledgeDocument {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "uploaded_by", nullable = false)
  private UUID uploadedBy;

  @Column(nullable = false)
  private String filename;

  @Column(name = "mime_type", nullable = false)
  private String mimeType;

  @Column(name = "object_key", nullable = false)
  private String objectKey;

  /** The caller's declared checksum, verified against the actually-stored object at completion. */
  @Column private String sha256;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private KnowledgeDocumentStatus status = KnowledgeDocumentStatus.UPLOADING;

  @Column(name = "error_code")
  private String errorCode;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected KnowledgeDocument() {}

  public KnowledgeDocument(
      UUID organizationId,
      UUID uploadedBy,
      String filename,
      String mimeType,
      String objectKey,
      String sha256,
      long sizeBytes) {
    this.organizationId = organizationId;
    this.uploadedBy = uploadedBy;
    this.filename = filename;
    this.mimeType = mimeType;
    this.objectKey = objectKey;
    this.sha256 = sha256;
    this.sizeBytes = sizeBytes;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getUploadedBy() {
    return uploadedBy;
  }

  public String getFilename() {
    return filename;
  }

  public String getMimeType() {
    return mimeType;
  }

  public String getObjectKey() {
    return objectKey;
  }

  public String getSha256() {
    return sha256;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public KnowledgeDocumentStatus getStatus() {
    return status;
  }

  public void markQueued() {
    this.status = KnowledgeDocumentStatus.QUEUED;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
