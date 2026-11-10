package com.opspilot.agent;

import java.util.List;

/** Keyset page of runs. No total: it is not computed, so it is not claimed. */
public record AgentRunPageResponse(List<AgentRunResponse> items, String nextCursor) {}
