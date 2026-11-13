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

  /**
   * The cursor page, as a <em>native</em> query using a row-value comparison.
   *
   * <p>This is the one place in the repository that is not JPQL, and the reason is measured. T34
   * benchmarked the previous JPQL form — {@code createdAt < :ts OR (createdAt = :ts AND id < :id)}
   * — against the 10,000-ticket fixture in benchmarks/fixture.sql, at a cursor 5,000 rows in, with
   * the matching index in place:
   *
   * <pre>
   * OR form:               Index Scan + Filter, Rows Removed by Filter: 5001,
   *                        Buffers: shared hit=30250,  Execution Time: 4.109 ms
   * row-constructor form:  Index Scan, predicate inside Index Cond, no rows removed,
   *                        Buffers: shared hit=6,      Execution Time: 0.038 ms
   * </pre>
   *
   * <p>108x faster, and five thousand times fewer buffers. The difference is not the index — both
   * plans use it — but <em>where the predicate runs</em>. PostgreSQL cannot push an {@code OR} of
   * two comparisons into an index condition, so it walks the index from the newest row and throws
   * away everything before the cursor: the cost grows with how deep the page is, which is the
   * precise failure keyset pagination exists to avoid. {@code (created_at, id) < (?, ?)} is a
   * single row-value comparison that becomes an {@code Index Cond}, so the scan starts at the
   * cursor.
   *
   * <p>It is native because JPQL has no row-value syntax; this cannot be expressed in HQL at all.
   * The {@code CAST(? AS text)} wrappers on the optional filters are the native-query equivalent of
   * the type-inference problem described above — without them PostgreSQL cannot determine the
   * parameter type of a NULL that only ever appears in an {@code IS NULL} test.
   *
   * <p>{@code AgentRunRepository.findPageAfter} has the identical OR-shape and the same fix
   * applies; it is left alone here because it has not been measured, and this ticket's rule is that
   * an optimisation ships with its evidence.
   */
  @Query(
      value =
          """
          SELECT * FROM tickets
          WHERE organization_id = :organizationId
            AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
            AND (CAST(:priority AS text) IS NULL OR priority = CAST(:priority AS text))
            AND (created_at, id) < (:cursorCreatedAt, :cursorId)
          ORDER BY created_at DESC, id DESC
          LIMIT :limit
          """,
      nativeQuery = true)
  List<Ticket> findPageAfterNative(
      @Param("organizationId") UUID organizationId,
      @Param("status") String status,
      @Param("priority") String priority,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      @Param("limit") int limit);

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
