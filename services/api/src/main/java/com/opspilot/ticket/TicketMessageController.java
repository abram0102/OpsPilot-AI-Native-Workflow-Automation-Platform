package com.opspilot.ticket;

import com.opspilot.auth.AuthenticatedPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A ticket's internal conversation (docs/API_CONTRACT.md "Tickets"; docs/TASKS.md T10). */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/tickets/{ticketId}/messages")
@Tag(name = "Ticket messages")
class TicketMessageController {

  private final TicketMessageService ticketMessageService;

  TicketMessageController(TicketMessageService ticketMessageService) {
    this.ticketMessageService = ticketMessageService;
  }

  @Operation(summary = "List a ticket's internal conversation")
  @GetMapping
  List<TicketMessageResponse> list(
      @PathVariable UUID orgId,
      @PathVariable UUID ticketId,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return ticketMessageService.listMessages(principal, orgId, ticketId);
  }

  @Operation(summary = "Post a human-authored internal message")
  @PostMapping
  ResponseEntity<TicketMessageResponse> create(
      @PathVariable UUID orgId,
      @PathVariable UUID ticketId,
      @Valid @RequestBody CreateTicketMessageRequest request,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    TicketMessageResponse response =
        ticketMessageService.createMessage(principal, orgId, ticketId, request.body());
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }
}
