package com.opspilot.ticket;

import com.opspilot.auth.AuthenticatedPrincipal;
import java.util.List;
import java.util.UUID;

/** A ticket's internal conversation (docs/API_CONTRACT.md "Tickets"; docs/TASKS.md T10). */
public interface TicketMessageService {

  /**
   * @throws org.springframework.security.access.AccessDeniedException if the caller is not a member
   *     of {@code organizationId}.
   * @throws com.opspilot.common.web.NotFoundException if {@code ticketId} does not belong to {@code
   *     organizationId}.
   */
  List<TicketMessageResponse> listMessages(
      AuthenticatedPrincipal principal, UUID organizationId, UUID ticketId);

  /**
   * @throws org.springframework.security.access.AccessDeniedException if the caller is below {@code
   *     MEMBER} in {@code organizationId}.
   * @throws com.opspilot.common.web.NotFoundException if {@code ticketId} does not belong to {@code
   *     organizationId}.
   * @throws com.opspilot.common.web.ConflictException if the ticket is not {@code OPEN} or {@code
   *     IN_PROGRESS} (docs/DATA_MODEL.md: "A ticket may be replied to while OPEN or IN_PROGRESS").
   */
  TicketMessageResponse createMessage(
      AuthenticatedPrincipal principal, UUID organizationId, UUID ticketId, String body);
}
