package com.opspilot.knowledge;

import com.opspilot.auth.AuthenticatedPrincipal;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Knowledge document metadata and upload lifecycle (docs/API_CONTRACT.md "Knowledge"; docs/TASKS.md
 * T13).
 */
public interface KnowledgeService {

  /**
   * @throws org.springframework.security.access.AccessDeniedException if the caller is below {@code
   *     MEMBER} in {@code organizationId}.
   * @throws com.opspilot.common.web.BadRequestException if the extension/MIME/size aren't allowed
   *     (docs/API_CONTRACT.md: ".txt, .md, .pdf; max default 10 MiB").
   */
  CreateUploadSessionResponse createUploadSession(
      AuthenticatedPrincipal principal, UUID organizationId, CreateUploadSessionRequest request);

  /**
   * Local-adapter-only: accepts the actual bytes for a session {@code objectKey} identifies.
   *
   * @throws com.opspilot.common.web.NotFoundException if no such upload session exists in this
   *     organization.
   * @throws com.opspilot.common.web.ConflictException if the session was already completed.
   * @throws com.opspilot.storage.UploadRejectedException if the real content fails validation.
   */
  void receiveLocalUpload(
      AuthenticatedPrincipal principal, UUID organizationId, String objectKey, InputStream content);

  /**
   * Verifies the actually-stored object against what was declared and transitions {@code UPLOADING
   * -> QUEUED}, inserting an outbox event in the same transaction (docs/DATA_MODEL.md invariant
   * #7). Calling this again on an already-queued document is safe and returns the same result
   * (docs/SECURITY_AND_TESTING.md: "duplicate completion is safe").
   *
   * @throws com.opspilot.common.web.NotFoundException if {@code documentId} does not belong to
   *     {@code organizationId}.
   * @throws com.opspilot.common.web.ConflictException if the stored object is missing, or its
   *     actual size/checksum doesn't match what was declared.
   */
  KnowledgeDocumentResponse completeUpload(
      AuthenticatedPrincipal principal, UUID organizationId, UUID documentId);

  /**
   * @throws org.springframework.security.access.AccessDeniedException if the caller is not a member
   *     of {@code organizationId}.
   */
  List<KnowledgeDocumentResponse> listDocuments(
      AuthenticatedPrincipal principal, UUID organizationId);

  /**
   * @throws com.opspilot.common.web.NotFoundException if {@code documentId} does not belong to
   *     {@code organizationId}.
   */
  KnowledgeDocumentResponse getDocument(
      AuthenticatedPrincipal principal, UUID organizationId, UUID documentId);

  /**
   * @throws org.springframework.security.access.AccessDeniedException if the caller is below {@code
   *     ADMIN} in {@code organizationId}.
   * @throws com.opspilot.common.web.NotFoundException if {@code documentId} does not belong to
   *     {@code organizationId}.
   */
  void deleteDocument(AuthenticatedPrincipal principal, UUID organizationId, UUID documentId);
}
