package com.opspilot.ticket;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketTagRepository extends JpaRepository<TicketTag, UUID> {

  List<TicketTag> findAllByTicketIdAndOrganizationId(UUID ticketId, UUID organizationId);
}
