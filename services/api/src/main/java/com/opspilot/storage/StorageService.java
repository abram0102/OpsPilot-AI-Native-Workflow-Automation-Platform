package com.opspilot.storage;

import java.io.InputStream;
import java.util.UUID;

/**
 * Private object storage for knowledge documents (docs/TASKS.md T13). {@link
 * com.opspilot.knowledge.KnowledgeDocument#getObjectKey()} is always generated here, server-side —
 * never from a client-supplied filename (docs/SECURITY_AND_TESTING.md: "S3 key not derived from
 * filename"). Two implementations: {@link LocalFilesystemStorageService} (default — a real local
 * adapter, safe-path-checked) and {@link S3StorageService} (private bucket, signed URL; only active
 * when {@code storage.provider=s3} and AWS credentials are configured — untestable in this
 * environment, same Gate H3 limitation as every other AWS-dependent ticket).
 */
public interface StorageService {

  /**
   * A fresh, unguessable key that embeds the tenant so a key alone can never cross a tenant
   * boundary — never derived from the client's filename or any other client input.
   */
  String generateObjectKey(UUID organizationId);

  /**
   * Where the client should PUT the actual bytes: a presigned URL (S3) or this application's own
   * local-upload endpoint path (local adapter — see {@link #receiveUpload}).
   */
  UploadTarget createUploadTarget(
      UUID organizationId, String objectKey, String mimeType, long sizeBytes);

  boolean exists(String objectKey);

  /**
   * The object's actual stored size and checksum, read back from storage itself — never trusted
   * from what the caller originally declared.
   */
  StoredObjectMetadata metadataOf(String objectKey);

  void delete(String objectKey);

  /**
   * Accepts the actual bytes for a key this adapter issued, validating the real content against
   * what was declared (size cap, magic-byte sniffing — docs/SECURITY_AND_TESTING.md:
   * "MIME+magic-byte validation, file size limit... no executable files") before persisting
   * anything.
   *
   * <p>Only the local adapter supports this — a real S3 upload goes directly from the client to the
   * presigned URL and never touches this backend, so {@link S3StorageService} inherits this
   * default, which refuses the call rather than silently doing nothing.
   *
   * @throws UploadRejectedException if the actual content fails validation.
   */
  default StoredObjectMetadata receiveUpload(
      String objectKey, String declaredMimeType, long declaredSizeBytes, InputStream content) {
    throw new UnsupportedOperationException(
        "This storage adapter does not accept direct uploads through the backend.");
  }
}
