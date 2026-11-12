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
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
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

  /**
   * The caller left out a required query parameter.
   *
   * <p>Added in T28, after {@code SecurityRegressionSweepIntegrationTest} swept every mapped
   * endpoint and found {@code PUT .../knowledge/uploads/local-object} answering 500 when its
   * required {@code objectKey} was absent: without this handler the exception reached the catch-all
   * below. A malformed request is the caller's problem, and dressing it as a server failure is
   * wrong twice over — the caller is told to retry something that cannot work, and the 5xx rate
   * that docs/SECURITY_AND_TESTING.md §7 alerts on rises every time anyone probes the API, so a
   * scanner looks exactly like an outage.
   */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ApiErrorResponse> handleMissingParameter(
      MissingServletRequestParameterException ex, HttpServletRequest request) {
    return respond(
        HttpStatus.BAD_REQUEST,
        "VALIDATION_ERROR",
        "Request failed validation.",
        List.of(ex.getParameterName() + ": parameter is required."),
        request);
  }

  /**
   * Wrong verb for a path that exists. Spring's own status is already 405; without a handler the
   * {@code @RestControllerAdvice} catch-all would override it with a 500.
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
    return respond(
        HttpStatus.METHOD_NOT_ALLOWED,
        "METHOD_NOT_ALLOWED",
        "That method is not supported on this resource.",
        List.of(),
        request);
  }

  /** Body sent as a media type the endpoint does not read. Same reasoning as the 405 above. */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(
      HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
    return respond(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "UNSUPPORTED_MEDIA_TYPE",
        "That content type is not supported on this resource.",
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
