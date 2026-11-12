package com.opspilot.agent;

import com.opspilot.auth.AuthenticatedPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Run start, explorer and cancel (docs/API_CONTRACT.md "Agent runs, trace, approval"; docs/TASKS.md
 * T18).
 *
 * <p>Starting a run returns {@code 202}, not {@code 201}: the request has been accepted and durably
 * recorded, but nothing has executed it — the publisher is T24 and the worker T25. The status in
 * the response is therefore {@code QUEUED}, and {@code pollUrl} is where progress will appear.
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
@Tag(name = "Agent runs")
class AgentRunController {

  private final AgentRunService runService;

  AgentRunController(AgentRunService runService) {
    this.runService = runService;
  }

  @Operation(summary = "Request an agent run for a ticket")
  @PostMapping("/tickets/{ticketId}/runs")
  ResponseEntity<StartRunResponse> start(
      @PathVariable UUID orgId,
      @PathVariable UUID ticketId,
      @Valid @RequestBody StartRunRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    AgentRun run = runService.start(principal, orgId, ticketId, request.workflow(), idempotencyKey);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(StartRunResponse.from(run));
  }

  @Operation(summary = "List agent runs, optionally only one ticket's")
  @GetMapping("/runs")
  AgentRunPageResponse list(
      @PathVariable UUID orgId,
      @RequestParam(required = false) AgentRunStatus status,
      @RequestParam(required = false) UUID ticketId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return runService.list(principal, orgId, status, ticketId, cursor, limit);
  }

  @Operation(summary = "Get one agent run")
  @GetMapping("/runs/{runId}")
  AgentRunDetailResponse get(
      @PathVariable UUID orgId,
      @PathVariable UUID runId,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return AgentRunDetailResponse.from(runService.get(principal, orgId, runId));
  }

  @Operation(summary = "List a run's sanitized trace steps")
  @GetMapping("/runs/{runId}/steps")
  AgentStepPageResponse listSteps(
      @PathVariable UUID orgId,
      @PathVariable UUID runId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return runService.listSteps(principal, orgId, runId, cursor, limit);
  }

  @Operation(summary = "Cancel an agent run")
  @PostMapping("/runs/{runId}/cancel")
  AgentRunDetailResponse cancel(
      @PathVariable UUID orgId,
      @PathVariable UUID runId,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return AgentRunDetailResponse.from(runService.cancel(principal, orgId, runId));
  }
}
