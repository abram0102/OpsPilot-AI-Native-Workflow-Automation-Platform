package com.opspilot.ticket;

import com.opspilot.auth.AuthenticatedPrincipal;
import java.util.UUID;

/** Ticket CRUD and paginated listing (docs/API_CONTRACT.md "Tickets"; docs/TASKS.md T10). */
public interface TicketService {

  /**
   * @throws org.springframework.security.access.AccessDeniedException if the caller is below {@code
   *     MEMBER} in {@code organizationId}.
   * @throws com.opspilot.common.web.NotFoundException if {@code customerId} is given but does not
   *     belong to {@code organizationId}.
   */
  TicketResponse createTicket(
      AuthenticatedPrincipal principal, UUID organizationId, CreateTicketRequest request);

  /**
   * @throws org.springframework.security.access.AccessDeniedException if the caller is not a member
   *     of {@code organizationId}.
   * @throws com.opspilot.common.web.NotFoundException if {@code ticketId} does not belong to {@code
   *     organizationId}.
   */
  TicketDetailResponse getTicket(
      AuthenticatedPrincipal principal, UUID organizationId, UUID ticketId);

  /**
   * @throws org.springframework.security.access.AccessDeniedException if the caller is not a member
   *     of {@code organizationId}.
   * @throws com.opspilot.common.web.BadRequestException if {@code cursor} doesn't decode.
   */
  TicketPageResponse listTickets(
      AuthenticatedPrincipal principal,
      UUID organizationId,
      TicketStatus status,
      TicketPriority priority,
      String cursor,
      Integer limit);

  /**
   * @throws org.springframework.security.access.AccessDeniedException if the caller is below {@code
   *     MEMBER} in {@code organizationId}.
   * @throws com.opspilot.common.web.NotFoundException if {@code ticketId} (or a given {@code
   *     assigneeId}) does not belong to {@code organizationId}.
   * @throws com.opspilot.common.web.ConflictException if {@code request.version()} is stale, or the
   *     requested status change is not an allowed transition (docs/DATA_MODEL.md section C).
   */
  TicketResponse updateTicket(
      AuthenticatedPrincipal principal,
      UUID organizationId,
      UUID ticketId,
      UpdateTicketRequest request);
}
