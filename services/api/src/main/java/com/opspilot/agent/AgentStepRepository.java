package com.opspilot.agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentStepRepository extends JpaRepository<AgentStep, UUID> {

  List<AgentStep> findAllByRunIdAndOrganizationIdOrderBySequence(UUID runId, UUID organizationId);

  Optional<AgentStep> findByRunIdAndSequence(UUID runId, int sequence);

  long countByRunIdAndOrganizationId(UUID runId, UUID organizationId);

  /**
   * A page of a run's trace, in order.
   *
   * <p>Keyed on {@code sequence}, which is already unique within a run — so unlike the run list
   * this needs no composite cursor and no split query: an {@code Integer} bind has a column to
   * infer its type from, so {@code -1} serves as "from the beginning".
   */
  List<AgentStep> findAllByRunIdAndOrganizationIdAndSequenceGreaterThanOrderBySequence(
      UUID runId, UUID organizationId, int afterSequence, Pageable pageable);
}
