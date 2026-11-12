package com.opspilot.agent;

import com.opspilot.auth.AuthenticatedPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The approval inbox and its decisions (docs/API_CONTRACT.md; docs/TASKS.md T22).
 *
 * <p>Note what the decision endpoints do not take: a request body. docs/API_CONTRACT.md requires
 * that "Approval must not accept a replacement body from frontend", and the surest way to honour
 * that is to leave nothing for a body to substitute — the published text comes from the immutable
 * stored proposal, which only the worker ever reads.
 *
 * <p>A decision returns 200 with the decided approval, but nothing has been published at that
 * point: it records the decision and queues a resume event, and T23's worker re-checks the decision
 * before acting.
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/approvals")
@Tag(name = "Approvals")
class ApprovalController {

  private final ApprovalDecisionService decisionService;

  ApprovalController(ApprovalDecisionService decisionService) {
    this.decisionService = decisionService;
  }

  @Operation(summary = "List approvals awaiting a decision, or every approval of one run")
  @GetMapping
  List<ApprovalResponse> list(
      @PathVariable UUID orgId,
      @RequestParam(required = false) ApprovalStatus status,
      @RequestParam(required = false) UUID runId,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return decisionService.list(principal, orgId, status, runId);
  }

  @Operation(summary = "Get one approval with its exact proposal and evidence")
  @GetMapping("/{approvalId}")
  ApprovalResponse get(
      @PathVariable UUID orgId,
      @PathVariable UUID approvalId,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return decisionService.get(principal, orgId, approvalId);
  }

  @Operation(summary = "Approve the exact proposal as stored")
  @PostMapping("/{approvalId}/approve")
  ApprovalResponse approve(
      @PathVariable UUID orgId,
      @PathVariable UUID approvalId,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return decisionService.decide(principal, orgId, approvalId, ApprovalStatus.APPROVED);
  }

  @Operation(summary = "Reject the proposal")
  @PostMapping("/{approvalId}/reject")
  ApprovalResponse reject(
      @PathVariable UUID orgId,
      @PathVariable UUID approvalId,
      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return decisionService.decide(principal, orgId, approvalId, ApprovalStatus.REJECTED);
  }
}
