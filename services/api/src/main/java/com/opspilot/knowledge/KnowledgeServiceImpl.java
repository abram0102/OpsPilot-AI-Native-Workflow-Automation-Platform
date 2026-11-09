package com.opspilot.knowledge;

import com.opspilot.auth.AuthenticatedPrincipal;
import com.opspilot.auth.OrganizationAccessService;
import com.opspilot.auth.OrganizationAction;
import com.opspilot.common.outbox.OutboxEvent;
import com.opspilot.common.outbox.OutboxEventRepository;
import com.opspilot.common.web.BadRequestException;
import com.opspilot.common.web.ConflictException;
import com.opspilot.common.web.NotFoundException;
import com.opspilot.storage.StorageService;
import com.opspilot.storage.StoredObjectMetadata;
import com.opspilot.storage.UploadTarget;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class KnowledgeServiceImpl implements KnowledgeService {

  /** docs/API_CONTRACT.md: "Upload accept: .txt, .md, .pdf; max default 10 MiB." */
  private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

  private static final Map<String, Set<String>> ALLOWED_MIME_TYPES_BY_EXTENSION =
      Map.of(
          "txt", Set.of("text/plain"),
          "md", Set.of("text/plain", "text/markdown"),
          "pdf", Set.of("application/pdf"));

  private final KnowledgeDocumentRepository documents;
  private final OutboxEventRepository outboxEvents;
  private final StorageService storageService;
  private final OrganizationAccessService accessService;
  private final ObjectMapper objectMapper;

  public KnowledgeServiceImpl(
      KnowledgeDocumentRepository documents,
      OutboxEventRepository outboxEvents,
      StorageService storageService,
      OrganizationAccessService accessService,
      ObjectMapper objectMapper) {
    this.documents = documents;
    this.outboxEvents = outboxEvents;
    this.storageService = storageService;
    this.accessService = accessService;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional
  public CreateUploadSessionResponse createUploadSession(
      AuthenticatedPrincipal principal, UUID organizationId, CreateUploadSessionRequest request) {
    accessService.requireAction(principal, organizationId, OrganizationAction.UPLOAD_KNOWLEDGE);

    String extension = extensionOf(request.filename());
    Set<String> allowedMimeTypes = ALLOWED_MIME_TYPES_BY_EXTENSION.get(extension);
    if (allowedMimeTypes == null) {
      throw new BadRequestException("Unsupported file extension. Allowed: .txt, .md, .pdf.");
    }
    if (!allowedMimeTypes.contains(request.mimeType())) {
      throw new BadRequestException("The declared MIME type does not match the file extension.");
    }
    if (request.sizeBytes() > MAX_SIZE_BYTES) {
      throw new BadRequestException("File exceeds the 10 MiB upload limit.");
    }

    String objectKey = storageService.generateObjectKey(organizationId);
    KnowledgeDocument document =
        documents.save(
            new KnowledgeDocument(
                organizationId,
                principal.userId(),
                request.filename(),
                request.mimeType(),
                objectKey,
                request.sha256(),
                request.sizeBytes()));

    UploadTarget target =
        storageService.createUploadTarget(
            organizationId, objectKey, request.mimeType(), request.sizeBytes());
    return new CreateUploadSessionResponse(
        document.getId(), objectKey, target.uploadUrl(), target.uploadMethod());
  }

  @Override
  public void receiveLocalUpload(
      AuthenticatedPrincipal principal,
      UUID organizationId,
      String objectKey,
      InputStream content) {
    accessService.requireAction(principal, organizationId, OrganizationAction.UPLOAD_KNOWLEDGE);

    KnowledgeDocument document =
        documents
            .findByOrganizationIdAndObjectKey(organizationId, objectKey)
            .orElseThrow(() -> new NotFoundException("Upload session not found."));
    if (document.getStatus() != KnowledgeDocumentStatus.UPLOADING) {
      throw new ConflictException("This upload has already been completed.");
    }

    storageService.receiveUpload(
        objectKey, document.getMimeType(), document.getSizeBytes(), content);
  }

  @Override
  @Transactional
  public KnowledgeDocumentResponse completeUpload(
      AuthenticatedPrincipal principal, UUID organizationId, UUID documentId) {
    accessService.requireAction(principal, organizationId, OrganizationAction.UPLOAD_KNOWLEDGE);

    KnowledgeDocument document =
        documents
            .findByIdAndOrganizationId(documentId, organizationId)
            .orElseThrow(() -> new NotFoundException("Document not found."));

    if (document.getStatus() != KnowledgeDocumentStatus.UPLOADING) {
      // Already completed — docs/SECURITY_AND_TESTING.md: "duplicate completion is safe."
      return KnowledgeDocumentResponse.from(document);
    }

    if (!storageService.exists(document.getObjectKey())) {
      throw new ConflictException("The file has not been uploaded yet.");
    }
    StoredObjectMetadata actual = storageService.metadataOf(document.getObjectKey());
    if (actual.sizeBytes() != document.getSizeBytes()) {
      throw new ConflictException("The stored file's size does not match what was declared.");
    }
    if (actual.sha256() != null && !actual.sha256().equalsIgnoreCase(document.getSha256())) {
      throw new ConflictException("The stored file's checksum does not match what was declared.");
    }

    document.markQueued();
    KnowledgeDocument saved = documents.saveAndFlush(document);

    String payload =
        objectMapper.writeValueAsString(
            Map.of(
                "documentId",
                saved.getId().toString(),
                "organizationId",
                organizationId.toString()));
    outboxEvents.save(
        new OutboxEvent(
            "knowledge.document.queued",
            "knowledge_document",
            saved.getId(),
            organizationId,
            payload));

    return KnowledgeDocumentResponse.from(saved);
  }

  @Override
  public List<KnowledgeDocumentResponse> listDocuments(
      AuthenticatedPrincipal principal, UUID organizationId) {
    accessService.requireAction(principal, organizationId, OrganizationAction.VIEW_KNOWLEDGE);
    return documents.findAllByOrganizationIdOrderByCreatedAtDesc(organizationId).stream()
        .map(KnowledgeDocumentResponse::from)
        .toList();
  }

  @Override
  public KnowledgeDocumentResponse getDocument(
      AuthenticatedPrincipal principal, UUID organizationId, UUID documentId) {
    accessService.requireAction(principal, organizationId, OrganizationAction.VIEW_KNOWLEDGE);
    KnowledgeDocument document =
        documents
            .findByIdAndOrganizationId(documentId, organizationId)
            .orElseThrow(() -> new NotFoundException("Document not found."));
    return KnowledgeDocumentResponse.from(document);
  }

  @Override
  @Transactional
  public void deleteDocument(
      AuthenticatedPrincipal principal, UUID organizationId, UUID documentId) {
    accessService.requireAction(principal, organizationId, OrganizationAction.DELETE_KNOWLEDGE);
    KnowledgeDocument document =
        documents
            .findByIdAndOrganizationId(documentId, organizationId)
            .orElseThrow(() -> new NotFoundException("Document not found."));
    storageService.delete(document.getObjectKey());
    documents.delete(document);
  }

  private static String extensionOf(String filename) {
    int dot = filename.lastIndexOf('.');
    if (dot < 0 || dot == filename.length() - 1) {
      return "";
    }
    return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
  }
}
