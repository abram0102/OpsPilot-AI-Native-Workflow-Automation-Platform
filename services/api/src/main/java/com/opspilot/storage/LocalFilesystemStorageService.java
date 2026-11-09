package com.opspilot.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Real local-dev filesystem adapter (docs/TASKS.md T13) — the default and the only adapter actually
 * exercised by this project's tests, since the S3 adapter needs AWS credentials this environment
 * doesn't have (Gate H3, docs/MANUAL_SETUP.md). Every path is resolved against, and verified to
 * stay under, a single configured base directory — the "safe path" docs/TASKS.md asks for, actually
 * enforced (not just assumed from server-generated keys), and covered by a dedicated path-traversal
 * test.
 */
@Service
@ConditionalOnProperty(
    prefix = "storage",
    name = "provider",
    havingValue = "local",
    matchIfMissing = true)
public class LocalFilesystemStorageService implements StorageService {

  private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
  private static final byte[] WINDOWS_PE_MAGIC = {'M', 'Z'};
  private static final byte[] ELF_MAGIC = {0x7f, 'E', 'L', 'F'};

  private final Path baseDir;

  public LocalFilesystemStorageService(
      @Value("${storage.local-dir:.local-storage}") String localDir) {
    this.baseDir = Path.of(localDir).toAbsolutePath().normalize();
  }

  @Override
  public String generateObjectKey(UUID organizationId) {
    return "orgs/" + organizationId + "/knowledge/" + UUID.randomUUID();
  }

  @Override
  public UploadTarget createUploadTarget(
      UUID organizationId, String objectKey, String mimeType, long sizeBytes) {
    String uploadUrl =
        "/api/v1/organizations/"
            + organizationId
            + "/knowledge/uploads/local-object?objectKey="
            + URLEncoder.encode(objectKey, StandardCharsets.UTF_8);
    return new UploadTarget(uploadUrl, "PUT");
  }

  @Override
  public boolean exists(String objectKey) {
    return Files.isRegularFile(resolveSafePath(objectKey));
  }

  @Override
  public StoredObjectMetadata metadataOf(String objectKey) {
    Path path = resolveSafePath(objectKey);
    try {
      long size = Files.size(path);
      String sha256 = sha256Of(Files.newInputStream(path));
      return new StoredObjectMetadata(size, sha256);
    } catch (IOException e) {
      throw new StorageException("Could not read stored object metadata.", e);
    }
  }

  @Override
  public void delete(String objectKey) {
    try {
      Files.deleteIfExists(resolveSafePath(objectKey));
    } catch (IOException e) {
      throw new StorageException("Could not delete stored object.", e);
    }
  }

  @Override
  public StoredObjectMetadata receiveUpload(
      String objectKey, String declaredMimeType, long declaredSizeBytes, InputStream content) {
    Path finalPath = resolveSafePath(objectKey);
    Path tempPath =
        finalPath.resolveSibling(finalPath.getFileName() + ".upload-" + UUID.randomUUID());

    try {
      Files.createDirectories(finalPath.getParent());
      byte[] prefix;
      long actualSize;
      MessageDigest digest = newSha256Digest();

      try (var out = Files.newOutputStream(tempPath)) {
        byte[] buffer = new byte[8192];
        long total = 0;
        byte[] firstBytes = new byte[16];
        int firstBytesRead = 0;
        int read;
        while ((read = content.read(buffer)) != -1) {
          total += read;
          if (total > declaredSizeBytes) {
            throw new UploadRejectedException(
                "Uploaded content exceeds the declared size of " + declaredSizeBytes + " bytes.");
          }
          if (firstBytesRead < firstBytes.length) {
            int toCopy = Math.min(read, firstBytes.length - firstBytesRead);
            System.arraycopy(buffer, 0, firstBytes, firstBytesRead, toCopy);
            firstBytesRead += toCopy;
          }
          digest.update(buffer, 0, read);
          out.write(buffer, 0, read);
        }
        actualSize = total;
        prefix =
            firstBytesRead == firstBytes.length
                ? firstBytes
                : java.util.Arrays.copyOf(firstBytes, firstBytesRead);
      }

      validateContentMatchesDeclaredType(declaredMimeType, prefix);

      Files.move(
          tempPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      return new StoredObjectMetadata(actualSize, HexFormat.of().formatHex(digest.digest()));
    } catch (UploadRejectedException e) {
      deleteQuietly(tempPath);
      throw e;
    } catch (IOException e) {
      deleteQuietly(tempPath);
      throw new StorageException("Could not store the uploaded object.", e);
    }
  }

  private void validateContentMatchesDeclaredType(String declaredMimeType, byte[] prefix) {
    if (startsWith(prefix, WINDOWS_PE_MAGIC) || startsWith(prefix, ELF_MAGIC)) {
      throw new UploadRejectedException("This file looks like an executable and cannot be stored.");
    }
    if ("application/pdf".equals(declaredMimeType) && !startsWith(prefix, PDF_MAGIC)) {
      throw new UploadRejectedException("This file's content does not look like a valid PDF.");
    }
  }

  private static boolean startsWith(byte[] content, byte[] magic) {
    if (content.length < magic.length) {
      return false;
    }
    for (int i = 0; i < magic.length; i++) {
      if (content[i] != magic[i]) {
        return false;
      }
    }
    return true;
  }

  private Path resolveSafePath(String objectKey) {
    Path candidate = baseDir.resolve(objectKey).normalize();
    if (!candidate.startsWith(baseDir)) {
      throw new StorageException("Refusing to use an object key that escapes the storage root.");
    }
    return candidate;
  }

  private static String sha256Of(InputStream in) throws IOException {
    MessageDigest digest = newSha256Digest();
    try (in) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest newSha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available.", e);
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Best-effort cleanup of a rejected/failed upload's temp file.
    }
  }
}
