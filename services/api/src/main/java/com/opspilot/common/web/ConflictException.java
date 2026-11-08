package com.opspilot.common.web;

/**
 * The request is well-formed and authorized but conflicts with the resource's current state (a
 * taken slug, an already-accepted invitation, a last-owner demotion). Thrown only by our own
 * service code with safe, already-reviewed user-facing text. Mapped to {@code 409 STATE_CONFLICT}
 * by {@link GlobalExceptionHandler}.
 */
public class ConflictException extends RuntimeException {

  public ConflictException(String message) {
    super(message);
  }
}
