package com.opspilot.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An approval as the inbox and detail view show it (docs/API_CONTRACT.md's approval response).
 *
 * <p>{@code proposal} is the stored payload parsed back into an object rather than echoed as a JSON
 * string, so the reviewer's client reads exactly the structure a human is being asked to approve.
 * It is never accepted *from* a client — there is no request shape that carries one.
 *
 * <p>{@code evidence} is the citations the draft relied on, surfaced so a reviewer can judge the
 * proposal against its sources instead of taking the body on trust.
 */
public record ApprovalResponse(
    UUID id,
    UUID runId,
    UUID ticketId,
    String toolCallId,
    AgentActionType actionType,
    ApprovalStatus status,
    ProposalView proposal,
    List<EvidenceView> evidence,
    UUID decisionBy,
    Instant decisionAt,
    Instant createdAt,
    Instant updatedAt) {

  /** The reply text a human is approving. */
  public record ProposalView(String body) {}

  /** One cited chunk: enough to find the source, not a copy of it. */
  public record EvidenceView(UUID documentId, Integer chunkIndex, String title) {}
}
