package com.opspilot.knowledge;

import com.opspilot.auth.AuthenticatedPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Knowledge document upload sessions, completion and metadata (docs/API_CONTRACT.md "Knowledge";
 * docs/TASKS.md T13). {@code orgId} is an identifier, never authorization — every method resolves
 * the caller's actual membership server-side via {@link KnowledgeService} (AGENTS.md rule 11).
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/knowledge")
@Tag(name = "Knowledge")
class KnowledgeController {

  private final KnowledgeService knowledgeService;

  KnowledgeController(KnowledgeService knowledgeService) {
    this.knowledgeService = knowledgeService;
  }

  @Operation(summary = "Validate a proposed upload and get a place to PUT the bytes")
  @PostMapping("/uploads")
  ResponseEntity<CreateUploadSessionResponse> createUploadSession(
      @PathVariable UUID orgId,
      @Valid @RequestBody CreateUploadSessionRequest request,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    CreateUploadSessionResponse response =
        knowledgeService.createUploadSession(principal, orgId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  /**
   * Local-storage-adapter-only receiver for the actual bytes — never used when {@code
   * storage.provider=s3} (a real S3 upload goes directly to the presigned URL from {@link
   * #createUploadSession}, never through this backend).
   */
  @Operation(summary = "Local storage adapter only: upload the actual file bytes")
  @PutMapping("/uploads/local-object")
  ResponseEntity<Void> receiveLocalUpload(
      @PathVariable UUID orgId,
      @RequestParam String objectKey,
      InputStream content,
      @AuthenticationPrincipal AuthenticatedPrincipal principal)
      throws IOException {
    try (content) {
      knowledgeService.receiveLocalUpload(principal, orgId, objectKey, content);
    }
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "Confirm the upload and queue ingestion")
  @PostMapping("/documents/{docId}/complete")
  ResponseEntity<KnowledgeDocumentResponse> completeUpload(
      @PathVariable UUID orgId,
      @PathVariable UUID docId,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    KnowledgeDocumentResponse response = knowledgeService.completeUpload(principal, orgId, docId);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
  }

  @Operation(summary = "List an organization's knowledge documents")
  @GetMapping("/documents")
  List<KnowledgeDocumentResponse> list(
      @PathVariable UUID orgId, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return knowledgeService.listDocuments(principal, orgId);
  }

  @Operation(summary = "A knowledge document's status and metadata")
  @GetMapping("/documents/{docId}")
  KnowledgeDocumentResponse get(
      @PathVariable UUID orgId,
      @PathVariable UUID docId,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return knowledgeService.getDocument(principal, orgId, docId);
  }

  @Operation(summary = "Delete a knowledge document and its stored object")
  @DeleteMapping("/documents/{docId}")
  ResponseEntity<Void> delete(
      @PathVariable UUID orgId,
      @PathVariable UUID docId,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    knowledgeService.deleteDocument(principal, orgId, docId);
    return ResponseEntity.noContent().build();
  }
}
