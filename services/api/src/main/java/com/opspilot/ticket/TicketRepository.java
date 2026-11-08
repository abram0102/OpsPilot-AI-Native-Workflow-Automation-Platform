package com.opspilot.ticket;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

  Optional<Ticket> findByIdAndOrganizationId(UUID id, UUID organizationId);

  /**
   * Keyset ("cursor") pagination, stable by {@code (created_at, id)} descending (docs/TASKS.md T10)
   * — never offset-based, so a page is never skipped/duplicated by a concurrent insert the way
   * {@code OFFSET} pagination would be. {@code status}/{@code priority} are optional filters;
   * {@code cursorCreatedAt}/{@code cursorId} are both null for the first page. {@code pageable} is
   * used only to cap the result size ({@code PageRequest.of(0, n)}) — callers fetch one row more
   * than the page size to detect whether a next page exists.
   */
  @Query(
      """
      SELECT t FROM Ticket t
      WHERE t.organizationId = :organizationId
        AND (:status IS NULL OR t.status = :status)
        AND (:priority IS NULL OR t.priority = :priority)
        AND (
          :cursorCreatedAt IS NULL
          OR t.createdAt < :cursorCreatedAt
          OR (t.createdAt = :cursorCreatedAt AND t.id < :cursorId)
        )
      ORDER BY t.createdAt DESC, t.id DESC
      """)
  List<Ticket> findPage(
      @Param("organizationId") UUID organizationId,
      @Param("status") TicketStatus status,
      @Param("priority") TicketPriority priority,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);
}
