package com.opspilot.common.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

  long countByAggregateTypeAndAggregateId(String aggregateType, UUID aggregateId);

  /**
   * Claims a batch of publishable rows, skipping any another publisher already holds.
   *
   * <p>A native query because Spring Data's {@code @Lock(PESSIMISTIC_WRITE)} emits {@code FOR
   * UPDATE} without {@code SKIP LOCKED}, and without the skip two publishers would block on each
   * other instead of dividing the work. With it, concurrent instances claim disjoint sets and
   * neither waits — which is the property the ticket asks for by name.
   *
   * <p>Ordered oldest-first so a backlog drains in the order it accrued, and bounded by {@code
   * limit} so one tick cannot hold a lock over the whole table. Relies on the {@code (status,
   * next_attempt_at)} index V003 already created.
   */
  @Query(
      value =
          """
          SELECT * FROM outbox_events
           WHERE status = 'PENDING'
             AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
           ORDER BY created_at
           LIMIT :limit
           FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  List<OutboxEvent> claimPublishable(@Param("now") Instant now, @Param("limit") int limit);

  long countByStatus(OutboxEventStatus status);
}
