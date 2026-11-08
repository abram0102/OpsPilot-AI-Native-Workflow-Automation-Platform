package com.opspilot.common.web;

/**
 * A requested resource does not exist, or the caller must not be told whether it does (AGENTS.md: a
 * mismatch "is consistently 403 or conceal with 404"). Thrown only by our own service code with
 * safe, already-reviewed user-facing text — never built from a caught lower-level exception's own
 * message. Mapped to {@code 404 NOT_FOUND} by {@link GlobalExceptionHandler}.
 */
public class NotFoundException extends RuntimeException {

  public NotFoundException(String message) {
    super(message);
  }
}
