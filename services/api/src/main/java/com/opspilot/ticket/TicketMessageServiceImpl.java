package com.opspilot.ticket;

import com.opspilot.auth.AuthenticatedPrincipal;
import com.opspilot.auth.OrganizationAccessService;
import com.opspilot.auth.OrganizationAction;
import com.opspilot.common.web.ConflictException;
import com.opspilot.common.web.NotFoundException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketMessageServiceImpl implements TicketMessageService {

  private static final Set<TicketStatus> REPLYABLE_STATUSES =
      Set.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS);

  private final TicketRepository tickets;
  private final TicketMessageRepository messages;
  private final OrganizationAccessService accessService;

  public TicketMessageServiceImpl(
      TicketRepository tickets,
      TicketMessageRepository messages,
      OrganizationAccessService accessService) {
    this.tickets = tickets;
    this.messages = messages;
    this.accessService = accessService;
  }

  @Override
  public List<TicketMessageResponse> listMessages(
      AuthenticatedPrincipal principal, UUID organizationId, UUID ticketId) {
    accessService.requireAction(principal, organizationId, OrganizationAction.VIEW_TICKETS);
    requireTicket(organizationId, ticketId);
    return messages
        .findAllByTicketIdAndOrganizationIdOrderByCreatedAtAsc(ticketId, organizationId)
        .stream()
        .map(TicketMessageResponse::from)
        .toList();
  }

  @Override
  @Transactional
  public TicketMessageResponse createMessage(
      AuthenticatedPrincipal principal, UUID organizationId, UUID ticketId, String body) {
    accessService.requireAction(principal, organizationId, OrganizationAction.MANAGE_TICKETS);
    Ticket ticket = requireTicket(organizationId, ticketId);

    if (!REPLYABLE_STATUSES.contains(ticket.getStatus())) {
      throw new ConflictException(
          "This ticket is " + ticket.getStatus() + " and cannot be replied to.");
    }

    TicketMessage message =
        messages.save(TicketMessage.fromUser(organizationId, ticketId, principal.userId(), body));
    return TicketMessageResponse.from(message);
  }

  private Ticket requireTicket(UUID organizationId, UUID ticketId) {
    return tickets
        .findByIdAndOrganizationId(ticketId, organizationId)
        .orElseThrow(() -> new NotFoundException("Ticket not found."));
  }
}
