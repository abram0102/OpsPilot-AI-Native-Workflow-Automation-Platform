package com.opspilot.agent;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalRepository extends JpaRepository<Approval, UUID> {

  Optional<Approval> findByIdAndOrganizationId(UUID id, UUID organizationId);

  Optional<Approval> findByRunIdAndToolCallId(UUID runId, String toolCallId);

  /**
   * When the longest-waiting approval in this state was created, or null if there are none.
   *
   * <p>The oldest rather than an average (T30): "is anything waiting too long" is the question an
   * operator actually has, and a mean hides it — nineteen fresh approvals and one three-day-old one
   * average out to something that looks fine.
   *
   * <p>Deliberately not scoped to an organization: this is an operational metric for whoever runs
   * the service, never a tenant-facing figure, and it is served only from the authenticated metrics
   * endpoint.
   */
  @Query("SELECT MIN(a.createdAt) FROM Approval a WHERE a.status = :status")
  Instant oldestCreatedAtByStatus(@Param("status") ApprovalStatus status);

  List<Approval> findAllByOrganizationIdAndStatusOrderByCreatedAt(
      UUID organizationId, ApprovalStatus status);

  List<Approval> findAllByRunIdAndOrganizationIdOrderByCreatedAt(UUID runId, UUID organizationId);

  /**
   * Locks the row for the duration of a decision, so two reviewers clicking at once serialize
   * instead of racing. The status check in the service then rejects whichever arrives second.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM Approval a WHERE a.id = :id AND a.organizationId = :organizationId")
  Optional<Approval> findByIdAndOrganizationIdForUpdate(UUID id, UUID organizationId);
}
