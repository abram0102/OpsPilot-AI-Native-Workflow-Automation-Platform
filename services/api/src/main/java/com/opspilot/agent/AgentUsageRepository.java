package com.opspilot.agent;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentUsageRepository extends JpaRepository<AgentUsage, UUID> {

  List<AgentUsage> findAllByRunIdAndOrganizationIdOrderByCreatedAt(UUID runId, UUID organizationId);
}
