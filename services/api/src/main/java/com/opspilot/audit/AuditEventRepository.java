package com.opspilot.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

  List<AuditEvent> findAllByOrganizationIdOrderByOccurredAtDesc(UUID organizationId);

  List<AuditEvent> findAllByOrganizationIdAndTargetTypeAndTargetIdOrderByOccurredAtDesc(
      UUID organizationId, String targetType, UUID targetId);
}
