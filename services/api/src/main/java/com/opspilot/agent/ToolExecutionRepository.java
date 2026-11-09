package com.opspilot.agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolExecutionRepository extends JpaRepository<ToolExecution, UUID> {

  Optional<ToolExecution> findByIdAndOrganizationId(UUID id, UUID organizationId);

  Optional<ToolExecution> findByRunIdAndToolCallId(UUID runId, String toolCallId);

  List<ToolExecution> findAllByRunIdAndOrganizationIdOrderByCreatedAt(
      UUID runId, UUID organizationId);
}
