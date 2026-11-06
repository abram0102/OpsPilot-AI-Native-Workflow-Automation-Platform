package com.opspilot.organization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

  Optional<Invitation> findByTokenHash(String tokenHash);

  List<Invitation> findAllByOrganizationId(UUID organizationId);
}
