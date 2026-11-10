package com.opspilot.agent;

import java.util.List;

/** Keyset page of a run's steps, cursored on {@code sequence}. */
public record AgentStepPageResponse(List<AgentStepResponse> items, String nextCursor) {}
