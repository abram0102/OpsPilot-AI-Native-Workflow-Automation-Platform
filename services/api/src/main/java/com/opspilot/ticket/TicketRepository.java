package com.opspilot.ticket;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

  Optional<Ticket> findByIdAndOrganizationId(UUID id, UUID organizationId);

  Page<Ticket> findAllByOrganizationId(UUID organizationId, Pageable pageable);

  Page<Ticket> findAllByOrganizationIdAndStatus(
      UUID organizationId, TicketStatus status, Pageable pageable);
}
