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
   * {@code OFFSET} pagination would be. {@code status}/{@code priority} are optional filters.
   * {@code pageable} is used only to cap the result size ({@code PageRequest.of(0, n)}) — callers
   * fetch one row more than the page size to detect whether a next page exists.
   *
   * <p>Split into {@link #findFirstPage} and {@link #findPageAfter} rather than one query taking a
   * nullable cursor: binding a null {@code java.time.Instant} whose only textual occurrence is an
   * {@code IS NULL} check (no column to infer a type from) makes Postgres reject the query with
   * "could not determine data type of parameter" — confirmed by a real CI failure, not a
   * hypothetical (enum columns tolerate this fine; {@code timestamptz} does not).
   */
  @Query(
      """
      SELECT t FROM Ticket t
      WHERE t.organizationId = :organizationId
        AND (:status IS NULL OR t.status = :status)
        AND (:priority IS NULL OR t.priority = :priority)
      ORDER BY t.createdAt DESC, t.id DESC
      """)
  List<Ticket> findFirstPage(
      @Param("organizationId") UUID organizationId,
      @Param("status") TicketStatus status,
      @Param("priority") TicketPriority priority,
      Pageable pageable);

  @Query(
      """
      SELECT t FROM Ticket t
      WHERE t.organizationId = :organizationId
        AND (:status IS NULL OR t.status = :status)
        AND (:priority IS NULL OR t.priority = :priority)
        AND (
          t.createdAt < :cursorCreatedAt
          OR (t.createdAt = :cursorCreatedAt AND t.id < :cursorId)
        )
      ORDER BY t.createdAt DESC, t.id DESC
      """)
  List<Ticket> findPageAfter(
      @Param("organizationId") UUID organizationId,
      @Param("status") TicketStatus status,
      @Param("priority") TicketPriority priority,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);
}
