package com.opspilot.agent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {

  Optional<AgentRun> findByIdAndOrganizationId(UUID id, UUID organizationId);

  List<AgentRun> findAllByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

  List<AgentRun> findAllByOrganizationIdAndTicketIdOrderByCreatedAtDesc(
      UUID organizationId, UUID ticketId);

  Optional<AgentRun> findByOrganizationIdAndIdempotencyKey(
      UUID organizationId, String idempotencyKey);

  /**
   * Runs a worker may claim: not terminal, and either unleased or holding a lease that has run out.
   * This is the query the reclaimer in T25 needs, and the reason V004 indexes {@code (status,
   * lease_expires_at)} over exactly the non-terminal states.
   */
  @Query(
      """
      SELECT r FROM AgentRun r
       WHERE r.status IN :statuses
         AND (r.leaseExpiresAt IS NULL OR r.leaseExpiresAt <= :now)
       ORDER BY r.createdAt
      """)
  List<AgentRun> findClaimable(
      @Param("statuses") List<AgentRunStatus> statuses, @Param("now") Instant now);
}
