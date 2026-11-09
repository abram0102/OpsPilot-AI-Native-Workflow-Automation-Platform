package com.opspilot.agent;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ApprovalRepository extends JpaRepository<Approval, UUID> {

  Optional<Approval> findByIdAndOrganizationId(UUID id, UUID organizationId);

  Optional<Approval> findByRunIdAndToolCallId(UUID runId, String toolCallId);

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
