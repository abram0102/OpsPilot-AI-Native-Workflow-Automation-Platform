package com.opspilot.organization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

  Optional<Membership> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

  List<Membership> findAllByOrganizationId(UUID organizationId);

  /** Every organization a given user belongs to — backs {@code GET /api/v1/me}. */
  List<Membership> findAllByUserId(UUID userId);

  long countByOrganizationIdAndRole(UUID organizationId, MembershipRole role);
}
