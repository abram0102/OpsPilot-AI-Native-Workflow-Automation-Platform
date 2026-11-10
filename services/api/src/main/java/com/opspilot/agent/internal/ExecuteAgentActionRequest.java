package com.opspilot.agent.internal;

import com.opspilot.agent.AgentActionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

/**
 * docs/API_CONTRACT.md: {@code {runId, toolCallId, actionType, arguments}}.
 *
 * <p>Note what is <em>not</em> here: no {@code organizationId}, no {@code ticketId}, no approval
 * status. The contract calls those "untrusted hints" when a worker sends them, and the simplest way
 * to never trust a hint is to give it nowhere to arrive — every one of them is reconstructed from
 * the persisted run instead.
 */
public record ExecuteAgentActionRequest(
    @NotNull UUID runId,
    @NotBlank String toolCallId,
    @NotNull AgentActionType actionType,
    Map<String, String> arguments) {

  public Map<String, String> safeArguments() {
    return arguments == null ? Map.of() : arguments;
  }
}
