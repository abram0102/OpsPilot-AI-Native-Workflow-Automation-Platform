package com.opspilot.agent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
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

  /**
   * First page of the run explorer, newest first.
   *
   * <p>Split from {@link #findPageAfter} for the same reason the ticket queries are (see {@code
   * TicketRepository}): binding a null {@code Instant} whose only textual occurrence is an {@code
   * IS NULL} check gives Postgres no column to infer a type from, and it rejects the query
   * outright. A nullable *enum* or *uuid* filter is fine in both — each is compared against a typed
   * column in the same predicate — which is why {@code status} and {@code ticketId} stay in one
   * place and only the cursor is split out.
   */
  @Query(
      """
      SELECT r FROM AgentRun r
       WHERE r.organizationId = :organizationId
         AND (:status IS NULL OR r.status = :status)
         AND (:ticketId IS NULL OR r.ticketId = :ticketId)
       ORDER BY r.createdAt DESC, r.id DESC
      """)
  List<AgentRun> findFirstPage(
      @Param("organizationId") UUID organizationId,
      @Param("status") AgentRunStatus status,
      @Param("ticketId") UUID ticketId,
      Pageable pageable);

  @Query(
      """
      SELECT r FROM AgentRun r
       WHERE r.organizationId = :organizationId
         AND (:status IS NULL OR r.status = :status)
         AND (:ticketId IS NULL OR r.ticketId = :ticketId)
         AND (r.createdAt < :cursorCreatedAt
              OR (r.createdAt = :cursorCreatedAt AND r.id < :cursorId))
       ORDER BY r.createdAt DESC, r.id DESC
      """)
  List<AgentRun> findPageAfter(
      @Param("organizationId") UUID organizationId,
      @Param("status") AgentRunStatus status,
      @Param("ticketId") UUID ticketId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);
}
