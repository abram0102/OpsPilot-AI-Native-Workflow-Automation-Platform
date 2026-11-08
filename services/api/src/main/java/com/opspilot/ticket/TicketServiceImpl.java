package com.opspilot.ticket;

import com.opspilot.auth.AuthenticatedPrincipal;
import com.opspilot.auth.OrganizationAccessService;
import com.opspilot.auth.OrganizationAction;
import com.opspilot.common.web.ConflictException;
import com.opspilot.common.web.NotFoundException;
import com.opspilot.organization.MembershipRepository;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketServiceImpl implements TicketService {

  static final int DEFAULT_PAGE_SIZE = 25;
  static final int MAX_PAGE_SIZE = 100;

  /** docs/DATA_MODEL.md section C: the only allowed ticket status transitions. */
  private static final Map<TicketStatus, Set<TicketStatus>> ALLOWED_TRANSITIONS =
      new EnumMap<>(
          Map.of(
              TicketStatus.OPEN, EnumSet.of(TicketStatus.IN_PROGRESS),
              TicketStatus.IN_PROGRESS, EnumSet.of(TicketStatus.RESOLVED),
              TicketStatus.RESOLVED, EnumSet.of(TicketStatus.CLOSED, TicketStatus.IN_PROGRESS),
              TicketStatus.CLOSED, EnumSet.noneOf(TicketStatus.class)));

  private final TicketRepository tickets;
  private final CustomerRepository customers;
  private final TicketTagRepository tags;
  private final TicketMessageRepository messages;
  private final MembershipRepository memberships;
  private final OrganizationAccessService accessService;

  public TicketServiceImpl(
      TicketRepository tickets,
      CustomerRepository customers,
      TicketTagRepository tags,
      TicketMessageRepository messages,
      MembershipRepository memberships,
      OrganizationAccessService accessService) {
    this.tickets = tickets;
    this.customers = customers;
    this.tags = tags;
    this.messages = messages;
    this.memberships = memberships;
    this.accessService = accessService;
  }

  @Override
  @Transactional
  public TicketResponse createTicket(
      AuthenticatedPrincipal principal, UUID organizationId, CreateTicketRequest request) {
    accessService.requireAction(principal, organizationId, OrganizationAction.MANAGE_TICKETS);

    UUID customerId;
    if (request.customerId() != null) {
      if (!customers.existsByIdAndOrganizationId(request.customerId(), organizationId)) {
        throw new NotFoundException("Customer not found.");
      }
      customerId = request.customerId();
    } else {
      Customer customer =
          customers.save(
              new Customer(organizationId, request.customerName().trim(), request.customerEmail()));
      customerId = customer.getId();
    }

    TicketPriority priority =
        request.priority() != null ? request.priority() : TicketPriority.MEDIUM;
    Ticket ticket =
        tickets.saveAndFlush(
            new Ticket(
                organizationId,
                customerId,
                request.subject(),
                request.description(),
                priority,
                principal.userId()));
    return TicketResponse.from(ticket);
  }

  @Override
  public TicketDetailResponse getTicket(
      AuthenticatedPrincipal principal, UUID organizationId, UUID ticketId) {
    accessService.requireAction(principal, organizationId, OrganizationAction.VIEW_TICKETS);
    Ticket ticket = findTicket(organizationId, ticketId);
    List<String> ticketTags =
        tags.findAllByTicketIdAndOrganizationId(ticketId, organizationId).stream()
            .map(TicketTag::getTag)
            .toList();
    long messageCount = messages.countByTicketIdAndOrganizationId(ticketId, organizationId);
    return TicketDetailResponse.from(ticket, ticketTags, messageCount);
  }

  @Override
  public TicketPageResponse listTickets(
      AuthenticatedPrincipal principal,
      UUID organizationId,
      TicketStatus status,
      TicketPriority priority,
      String cursor,
      Integer limit) {
    accessService.requireAction(principal, organizationId, OrganizationAction.VIEW_TICKETS);

    int pageSize = normalizePageSize(limit);
    TicketCursors.Decoded decoded =
        (cursor == null || cursor.isBlank()) ? null : TicketCursors.decode(cursor);

    List<Ticket> rows =
        decoded == null
            ? tickets.findFirstPage(
                organizationId, status, priority, PageRequest.of(0, pageSize + 1))
            : tickets.findPageAfter(
                organizationId,
                status,
                priority,
                decoded.createdAt(),
                decoded.id(),
                PageRequest.of(0, pageSize + 1));

    boolean hasMore = rows.size() > pageSize;
    List<Ticket> page = hasMore ? rows.subList(0, pageSize) : rows;
    String nextCursor =
        hasMore
            ? TicketCursors.encode(
                page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getId())
            : null;

    return new TicketPageResponse(page.stream().map(TicketResponse::from).toList(), nextCursor);
  }

  @Override
  @Transactional
  public TicketResponse updateTicket(
      AuthenticatedPrincipal principal,
      UUID organizationId,
      UUID ticketId,
      UpdateTicketRequest request) {
    accessService.requireAction(principal, organizationId, OrganizationAction.MANAGE_TICKETS);
    Ticket ticket = findTicket(organizationId, ticketId);

    if (!request.version().equals(ticket.getVersion())) {
      throw new ConflictException(
          "This ticket was changed by someone else — reload and try again.");
    }

    if (request.status() != null && request.status() != ticket.getStatus()) {
      if (!ALLOWED_TRANSITIONS.get(ticket.getStatus()).contains(request.status())) {
        throw new ConflictException(
            "Cannot move a ticket from " + ticket.getStatus() + " to " + request.status() + ".");
      }
      ticket.setStatus(request.status());
    }

    if (request.priority() != null) {
      ticket.setPriority(request.priority());
    }

    if (request.assigneeId() != null) {
      if (memberships
          .findByOrganizationIdAndUserId(organizationId, request.assigneeId())
          .isEmpty()) {
        throw new NotFoundException("Assignee is not a member of this organization.");
      }
      ticket.setAssigneeId(request.assigneeId());
    }

    return TicketResponse.from(tickets.saveAndFlush(ticket));
  }

  private Ticket findTicket(UUID organizationId, UUID ticketId) {
    return tickets
        .findByIdAndOrganizationId(ticketId, organizationId)
        .orElseThrow(() -> new NotFoundException("Ticket not found."));
  }

  private static int normalizePageSize(Integer requested) {
    if (requested == null) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.max(1, Math.min(requested, MAX_PAGE_SIZE));
  }
}
