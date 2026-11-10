package com.opspilot.agent;

import jakarta.validation.constraints.NotNull;

/**
 * docs/API_CONTRACT.md: {@code {"workflow": "SUPPORT_TRIAGE_V1"}}.
 *
 * <p>A typed enum means an unknown workflow is rejected at the edge with a 400 rather than stored
 * as a run nothing can execute.
 */
public record StartRunRequest(@NotNull Workflow workflow) {}
