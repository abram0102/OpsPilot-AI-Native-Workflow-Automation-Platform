package com.opspilot.agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ToolExecutionRepository extends JpaRepository<ToolExecution, UUID> {

  Optional<ToolExecution> findByIdAndOrganizationId(UUID id, UUID organizationId);

  Optional<ToolExecution> findByRunIdAndToolCallId(UUID runId, String toolCallId);

  List<ToolExecution> findAllByRunIdAndOrganizationIdOrderByCreatedAt(
      UUID runId, UUID organizationId);

  /**
   * Claims the one row that may exist for a logical call, without ever raising a conflict.
   *
   * <p>Replaces check-then-insert (T26). Ten concurrent deliveries of the same {@code toolCallId}
   * all found nothing and all inserted, so nine met {@code uq_tool_executions_run_tool_call} as a
   * {@code DataIntegrityViolationException} — a 500 to the worker instead of the stored outcome,
   * and a transaction already marked rollback-only before anything could recover from it.
   *
   * <p>{@code ON CONFLICT DO NOTHING} turns that race into a wait. A second caller arriving while
   * the first is still in flight blocks on the unique index until that transaction ends, which
   * makes the index itself the serialization point — no in-memory lock, exactly as AGENTS.md
   * requires. Returns the number of rows inserted: 1 for the caller that created it, 0 for everyone
   * who found it already there.
   *
   * <p>Native because JPQL has no {@code ON CONFLICT}, and {@code status} is deliberately left to
   * the column default so there is one definition of "a new execution starts PROPOSED".
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO tool_executions (organization_id, run_id, tool_call_id, action_type)
               VALUES (:organizationId, :runId, :toolCallId, :actionType)
          ON CONFLICT (run_id, tool_call_id) DO NOTHING
          """,
      nativeQuery = true)
  int insertIfAbsent(
      @Param("organizationId") UUID organizationId,
      @Param("runId") UUID runId,
      @Param("toolCallId") String toolCallId,
      @Param("actionType") String actionType);

  /**
   * Locks the execution row for the rest of the transaction.
   *
   * <p>The insert above only serializes callers racing to *create* the row. Two retries of an
   * already-committed {@code FAILED_RETRYABLE} row would both pass the insert instantly and both go
   * on to apply the effect — and while a duplicate tag is absorbed by {@code
   * uq_ticket_tags_ticket_tag}, {@code ticket_messages} has no such constraint, so a duplicate
   * reply would simply be published twice. Holding the row makes the second caller wait and then
   * re-read a status that is no longer retryable.
   *
   * <p>Native, because Spring Data's {@code @Lock(PESSIMISTIC_WRITE)} on a derived query cannot be
   * combined with the organization scope and ordering this needs without a second round trip.
   */
  @Query(
      value =
          """
          SELECT * FROM tool_executions
           WHERE run_id = :runId AND tool_call_id = :toolCallId
             FOR UPDATE
          """,
      nativeQuery = true)
  Optional<ToolExecution> lockByRunIdAndToolCallId(
      @Param("runId") UUID runId, @Param("toolCallId") String toolCallId);
}
