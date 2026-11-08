package com.opspilot.ticket;

import com.opspilot.auth.AuthenticatedPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticket creation, detail, paginated listing and update (docs/API_CONTRACT.md "Tickets";
 * docs/TASKS.md T10). {@code orgId} is an identifier, never authorization — every method resolves
 * the caller's actual membership server-side via {@link TicketService} (AGENTS.md rule 11).
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/tickets")
@Tag(name = "Tickets")
class TicketController {

  private final TicketService ticketService;

  TicketController(TicketService ticketService) {
    this.ticketService = ticketService;
  }

  @Operation(summary = "Create a ticket, optionally creating its customer")
  @PostMapping
  ResponseEntity<TicketResponse> create(
      @PathVariable UUID orgId,
      @Valid @RequestBody CreateTicketRequest request,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    TicketResponse response = ticketService.createTicket(principal, orgId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @Operation(summary = "Paginate an organization's tickets, filterable by status/priority")
  @GetMapping
  TicketPageResponse list(
      @PathVariable UUID orgId,
      @RequestParam(required = false) TicketStatus status,
      @RequestParam(required = false) TicketPriority priority,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return ticketService.listTickets(principal, orgId, status, priority, cursor, limit);
  }

  @Operation(summary = "Ticket detail, with its tags and a message-count summary")
  @GetMapping("/{ticketId}")
  TicketDetailResponse get(
      @PathVariable UUID orgId,
      @PathVariable UUID ticketId,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return ticketService.getTicket(principal, orgId, ticketId);
  }

  @Operation(summary = "Update a ticket's status/priority/assignee, with optimistic concurrency")
  @PatchMapping("/{ticketId}")
  TicketResponse update(
      @PathVariable UUID orgId,
      @PathVariable UUID ticketId,
      @Valid @RequestBody UpdateTicketRequest request,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return ticketService.updateTicket(principal, orgId, ticketId, request);
  }
}
