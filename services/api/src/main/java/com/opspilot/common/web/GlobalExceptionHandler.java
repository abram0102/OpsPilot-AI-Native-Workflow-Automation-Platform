package com.opspilot.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps exceptions to the single stable error envelope defined in docs/API_CONTRACT.md. Never
 * forwards an exception's raw message or a stack trace to the client (AGENTS.md: "A trace never
 * logs ... sensitive internals remain private") — only the handlers below decide what is safe to
 * disclose.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiErrorResponse> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    List<String> details =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
            .toList();
    return respond(
        HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request failed validation.", details, request);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    List<String> details =
        ex.getConstraintViolations().stream()
            .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
            .toList();
    return respond(
        HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request failed validation.", details, request);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiErrorResponse> handleMalformedBody(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    return respond(
        HttpStatus.BAD_REQUEST,
        "VALIDATION_ERROR",
        "Request body is missing or not valid JSON.",
        List.of(),
        request);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiErrorResponse> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest request) {
    return respond(
        HttpStatus.FORBIDDEN, "FORBIDDEN", "You cannot perform this action.", List.of(), request);
  }

  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleNotFound(
      NotFoundException ex, HttpServletRequest request) {
    return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), List.of(), request);
  }

  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<ApiErrorResponse> handleConflict(
      ConflictException ex, HttpServletRequest request) {
    return respond(HttpStatus.CONFLICT, "STATE_CONFLICT", ex.getMessage(), List.of(), request);
  }

  /** Same 409 as a state conflict, but its own stable code — clients branch on the code. */
  @ExceptionHandler(IdempotencyConflictException.class)
  public ResponseEntity<ApiErrorResponse> handleIdempotencyConflict(
      IdempotencyConflictException ex, HttpServletRequest request) {
    return respond(
        HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", ex.getMessage(), List.of(), request);
  }

  @ExceptionHandler(BadRequestException.class)
  public ResponseEntity<ApiErrorResponse> handleBadRequest(
      BadRequestException ex, HttpServletRequest request) {
    return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), List.of(), request);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
      MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    return respond(
        HttpStatus.BAD_REQUEST,
        "VALIDATION_ERROR",
        ex.getName() + ": invalid value.",
        List.of(),
        request);
  }

  /**
   * JPA's {@code @Version} column caught a genuinely concurrent write (defense-in-depth on top of
   * the explicit client-supplied version check in {@code TicketServiceImpl}).
   */
  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<ApiErrorResponse> handleOptimisticLockingFailure(
      OptimisticLockingFailureException ex, HttpServletRequest request) {
    return respond(
        HttpStatus.CONFLICT,
        "STATE_CONFLICT",
        "This record was changed by someone else — reload and try again.",
        List.of(),
        request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> handleUnexpected(
      Exception ex, HttpServletRequest request) {
    String requestId = RequestIdSupport.resolve(request);
    log.error("Unhandled exception [requestId={}]", requestId, ex);
    return respond(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "An unexpected error occurred.",
        List.of(),
        request);
  }

  private ResponseEntity<ApiErrorResponse> respond(
      HttpStatus status,
      String code,
      String message,
      List<String> details,
      HttpServletRequest request) {
    String requestId = RequestIdSupport.resolve(request);
    return ResponseEntity.status(status)
        .body(ApiErrorResponse.of(code, message, requestId, details));
  }
}
