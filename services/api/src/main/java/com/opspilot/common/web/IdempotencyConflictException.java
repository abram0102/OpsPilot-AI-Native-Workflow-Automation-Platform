package com.opspilot.common.web;

/**
 * An {@code Idempotency-Key} was reused with a different request body.
 *
 * <p>Deliberately not a subclass of {@link ConflictException}: that one maps to the {@code
 * STATE_CONFLICT} error code, while docs/API_CONTRACT.md requires this case to surface as {@code
 * 409 IDEMPOTENCY_CONFLICT}. Same status, different stable code — and clients branch on the code,
 * not the status.
 */
public class IdempotencyConflictException extends RuntimeException {

  public IdempotencyConflictException(String message) {
    super(message);
  }
}
