package com.opspilot.ticket;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/organizations/{orgId}/tickets} (docs/API_CONTRACT.md).
 * Either {@code customerId} (an existing, same-tenant customer) or {@code customerName} (a brand
 * new customer created alongside the ticket — docs/TASKS.md T10's "customer creation") must be
 * given; never both treated as independent, and {@code customerId} always wins if present.
 */
public record CreateTicketRequest(
    UUID customerId,
    String customerName,
    String customerEmail,
    @NotBlank String subject,
    @NotBlank String description,
    TicketPriority priority) {

  @AssertTrue(message = "either customerId or customerName must be provided")
  public boolean isCustomerReferenceValid() {
    return customerId != null || (customerName != null && !customerName.isBlank());
  }
}
