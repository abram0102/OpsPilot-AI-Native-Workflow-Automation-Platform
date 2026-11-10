package com.opspilot.agent;

import java.util.UUID;

/**
 * docs/API_CONTRACT.md: {@code 202 {runId, status, pollUrl}}.
 *
 * <p>{@code pollUrl} is returned because the client has nowhere else to learn it — the run is
 * accepted, not finished, and this is the address where its progress appears.
 */
public record StartRunResponse(UUID runId, AgentRunStatus status, String pollUrl) {

  static StartRunResponse from(AgentRun run) {
    return new StartRunResponse(
        run.getId(),
        run.getStatus(),
        "/api/v1/organizations/" + run.getOrganizationId() + "/runs/" + run.getId());
  }
}
