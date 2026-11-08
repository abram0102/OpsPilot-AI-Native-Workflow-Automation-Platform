package com.opspilot.common.web;

/**
 * A request is malformed in a way bean validation doesn't cover (e.g. an opaque pagination cursor
 * that doesn't decode). Thrown only by our own service code with safe, already-reviewed user-facing
 * text. Mapped to {@code 400 VALIDATION_ERROR} by {@link GlobalExceptionHandler} — the same code
 * bean-validation failures use, since both are "the request itself is invalid."
 */
public class BadRequestException extends RuntimeException {

  public BadRequestException(String message) {
    super(message);
  }
}
