package com.opspilot.storage;

import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Real S3 adapter (docs/TASKS.md T13: "private bucket, signed URL when configured"), active only
 * when {@code storage.provider=s3} — never the default, and never testable in this environment: no
 * AWS tenant/bucket/credentials exist yet (Gate H3, docs/MANUAL_SETUP.md), the same honest
 * limitation this project has already recorded for Docker (Gate H0) and Auth0 (Gate H1). Written
 * against the real AWS SDK v2 API, not assumed, but only compile-verified here.
 *
 * <p><b>Known gap, documented not fixed:</b> {@link #metadataOf} returns a {@code null} checksum —
 * S3's {@code ETag} is not a reliable SHA-256 for multipart uploads, and verifying a real
 * server-side checksum needs the client to opt into S3's trailing-checksum feature when it PUTs to
 * the presigned URL, which this ticket does not implement or test. The completion flow
 * (docs/DECISIONS.md ADR) treats a {@code null} actual checksum as "size-verified only," never as a
 * checksum match — it never pretends to have verified a hash it didn't.
 */
@Service
@ConditionalOnProperty(prefix = "storage", name = "provider", havingValue = "s3")
public class S3StorageService implements StorageService {

  private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(15);

  private final S3Client s3Client;
  private final S3Presigner presigner;
  private final String bucket;

  public S3StorageService(
      @Value("${storage.s3.bucket}") String bucket, @Value("${storage.s3.region}") String region) {
    this.bucket = bucket;
    Region awsRegion = Region.of(region);
    this.s3Client = S3Client.builder().region(awsRegion).build();
    this.presigner = S3Presigner.builder().region(awsRegion).build();
  }

  @Override
  public String generateObjectKey(UUID organizationId) {
    return "orgs/" + organizationId + "/knowledge/" + UUID.randomUUID();
  }

  @Override
  public UploadTarget createUploadTarget(
      UUID organizationId, String objectKey, String mimeType, long sizeBytes) {
    PutObjectRequest putRequest =
        PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType(mimeType).build();
    PutObjectPresignRequest presignRequest =
        PutObjectPresignRequest.builder()
            .signatureDuration(UPLOAD_URL_TTL)
            .putObjectRequest(putRequest)
            .build();
    PresignedPutObjectRequest presigned = presigner.presignPutObject(presignRequest);
    return new UploadTarget(presigned.url().toString(), "PUT");
  }

  @Override
  public boolean exists(String objectKey) {
    try {
      s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
      return true;
    } catch (NoSuchKeyException e) {
      return false;
    }
  }

  @Override
  public StoredObjectMetadata metadataOf(String objectKey) {
    HeadObjectResponse head =
        s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
    return new StoredObjectMetadata(head.contentLength(), null);
  }

  @Override
  public void delete(String objectKey) {
    s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
  }
}
