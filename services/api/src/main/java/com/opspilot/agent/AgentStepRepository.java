package com.opspilot.agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentStepRepository extends JpaRepository<AgentStep, UUID> {

  List<AgentStep> findAllByRunIdAndOrganizationIdOrderBySequence(UUID runId, UUID organizationId);

  Optional<AgentStep> findByRunIdAndSequence(UUID runId, int sequence);

  long countByRunIdAndOrganizationId(UUID runId, UUID organizationId);
}
