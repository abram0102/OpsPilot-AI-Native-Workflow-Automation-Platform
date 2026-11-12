package com.opspilot.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure unit tests — no Spring context, no Docker, no Testcontainers — for the one storage adapter
 * this project can actually exercise in any environment (docs/TASKS.md T13's local-dev adapter).
 * Covers the acceptance line's storage-layer bullets directly: "reject 11 MiB file or unsupported
 * executable... never path-traverse, file persisted."
 */
@Tag("security")
class LocalFilesystemStorageServiceTest {

  @TempDir private Path tempDir;

  private LocalFilesystemStorageService newService() {
    return new LocalFilesystemStorageService(tempDir.toString());
  }

  @Test
  void generatedObjectKeysEmbedTheOrganizationAndNeverTheFilename() {
    LocalFilesystemStorageService storage = newService();
    UUID orgId = UUID.randomUUID();

    String key = storage.generateObjectKey(orgId);

    assertThat(key).contains(orgId.toString());
    assertThat(key).doesNotContain("..");
  }

  @Test
  void uploadTargetPointsAtTheLocalUploadEndpointForThisOrganization() {
    LocalFilesystemStorageService storage = newService();
    UUID orgId = UUID.randomUUID();
    String key = storage.generateObjectKey(orgId);

    UploadTarget target = storage.createUploadTarget(orgId, key, "text/plain", 10);

    assertThat(target.uploadMethod()).isEqualTo("PUT");
    assertThat(target.uploadUrl())
        .startsWith(
            "/api/v1/organizations/" + orgId + "/knowledge/uploads/local-object?objectKey=");
  }

  @Test
  void receivedUploadIsPersistedAndMetadataMatchesTheActualBytes() {
    LocalFilesystemStorageService storage = newService();
    String key = storage.generateObjectKey(UUID.randomUUID());
    byte[] content = "hello knowledge base".getBytes(StandardCharsets.UTF_8);

    StoredObjectMetadata stored =
        storage.receiveUpload(key, "text/plain", content.length, new ByteArrayInputStream(content));

    assertThat(stored.sizeBytes()).isEqualTo(content.length);
    assertThat(storage.exists(key)).isTrue();
    StoredObjectMetadata reread = storage.metadataOf(key);
    assertThat(reread.sizeBytes()).isEqualTo(content.length);
    assertThat(reread.sha256()).isEqualTo(stored.sha256());
  }

  @Test
  void contentExceedingTheDeclaredSizeIsRejectedAndNothingIsPersisted() {
    LocalFilesystemStorageService storage = newService();
    String key = storage.generateObjectKey(UUID.randomUUID());
    byte[] elevenMib = new byte[11 * 1024 * 1024];

    assertThatThrownBy(
            () -> storage.receiveUpload(key, "text/plain", 10, new ByteArrayInputStream(elevenMib)))
        .isInstanceOf(UploadRejectedException.class);
    assertThat(storage.exists(key)).isFalse();
  }

  @Test
  void contentThatLooksLikeAWindowsExecutableIsRejectedRegardlessOfDeclaredType() {
    LocalFilesystemStorageService storage = newService();
    String key = storage.generateObjectKey(UUID.randomUUID());
    byte[] exeMagic = {'M', 'Z', 0, 0, 0, 0};

    assertThatThrownBy(() ->
            storage.receiveUpload(
                key, "text/plain", exeMagic.length, new ByteArrayInputStream(exeMagic)))
        .isInstanceOf(UploadRejectedException.class);
    assertThat(storage.exists(key)).isFalse();
  }

  @Test
  void contentThatLooksLikeALinuxExecutableIsRejectedRegardlessOfDeclaredType() {
    LocalFilesystemStorageService storage = newService();
    String key = storage.generateObjectKey(UUID.randomUUID());
    byte[] elfMagic = {0x7f, 'E', 'L', 'F', 0, 0};

    assertThatThrownBy(() ->
            storage.receiveUpload(
                key, "application/pdf", elfMagic.length, new ByteArrayInputStream(elfMagic)))
        .isInstanceOf(UploadRejectedException.class);
  }

  @Test
  void aPdfDeclaredUploadWhoseContentIsNotActuallyAPdfIsRejected() {
    LocalFilesystemStorageService storage = newService();
    String key = storage.generateObjectKey(UUID.randomUUID());
    byte[] notAPdf = "this is just text".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() ->
            storage.receiveUpload(
                key, "application/pdf", notAPdf.length, new ByteArrayInputStream(notAPdf)))
        .isInstanceOf(UploadRejectedException.class);
  }

  @Test
  void aRealPdfMagicHeaderIsAccepted() {
    LocalFilesystemStorageService storage = newService();
    String key = storage.generateObjectKey(UUID.randomUUID());
    byte[] pdfLike = "%PDF-1.4 ...".getBytes(StandardCharsets.UTF_8);

    StoredObjectMetadata stored =
        storage.receiveUpload(
            key, "application/pdf", pdfLike.length, new ByteArrayInputStream(pdfLike));

    assertThat(stored.sizeBytes()).isEqualTo(pdfLike.length);
  }

  @Test
  void aCraftedObjectKeyCannotEscapeTheConfiguredStorageRoot() {
    LocalFilesystemStorageService storage = newService();
    String maliciousKey = "../../../../etc/passwd-clone";

    assertThatThrownBy(() -> storage.exists(maliciousKey)).isInstanceOf(StorageException.class);
    assertThatThrownBy(() ->
            storage.receiveUpload(
                maliciousKey,
                "text/plain",
                4,
                new ByteArrayInputStream("evil".getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(StorageException.class);
  }

  @Test
  void deletingAnObjectRemovesItFromDisk() throws IOException {
    LocalFilesystemStorageService storage = newService();
    String key = storage.generateObjectKey(UUID.randomUUID());
    byte[] content = "to be deleted".getBytes(StandardCharsets.UTF_8);
    storage.receiveUpload(key, "text/plain", content.length, new ByteArrayInputStream(content));
    assertThat(storage.exists(key)).isTrue();

    storage.delete(key);

    assertThat(storage.exists(key)).isFalse();
    // Nothing is left behind under the temp dir for this key (no stray temp files either).
    assertThat(Files.walk(tempDir).filter(Files::isRegularFile)).isEmpty();
  }
}
