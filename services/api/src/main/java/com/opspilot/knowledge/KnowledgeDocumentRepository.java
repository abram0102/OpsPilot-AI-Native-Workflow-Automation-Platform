package com.opspilot.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, UUID> {

  Optional<KnowledgeDocument> findByIdAndOrganizationId(UUID id, UUID organizationId);

  Optional<KnowledgeDocument> findByOrganizationIdAndObjectKey(
      UUID organizationId, String objectKey);

  List<KnowledgeDocument> findAllByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

  boolean existsByOrganizationIdAndObjectKey(UUID organizationId, String objectKey);
}
