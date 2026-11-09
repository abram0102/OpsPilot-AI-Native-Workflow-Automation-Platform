package com.opspilot.knowledge;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, UUID> {

  List<KnowledgeChunk> findAllByDocumentIdAndOrganizationId(UUID documentId, UUID organizationId);
}
