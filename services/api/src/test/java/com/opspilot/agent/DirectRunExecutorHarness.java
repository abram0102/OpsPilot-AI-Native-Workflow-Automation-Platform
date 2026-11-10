package com.opspilot.agent;

import java.util.UUID;

/**
 * Drives a queued run to completion synchronously, in-process (docs/TASKS.md T18: "a local
 * test-only direct executor harness that invokes worker on the queued run").
 *
 * <p><strong>This is test-scope code and is never shipped.</strong> It lives in {@code
 * src/test/java}, has no Spring stereotype, and is constructed by the test that uses it — so there
 * is no production code path into it and nothing to disable in a deployment. That is what keeps it
 * on the right side of AGENTS.md rule 7 ("no test-only production bypasses"): it adds a test
 * affordance, it does not weaken the product.
 *
 * <p><strong>What it does not do.</strong> It does not publish to Redis, consume a stream, or run a
 * LangGraph graph — the publisher is T24, the consumer T25 and the graph T19. It proves exactly one
 * thing: that a run row created by the API is consumable, and that status and trace changes
 * persist. Any claim beyond that would be fiction.
 *
 * <p>The steps it writes are placeholders in shape, not a simulation of real agent behaviour.
 */
final class DirectRunExecutorHarness {

  private final AgentRunService runService;
  private final AgentStepRepository steps;

  DirectRunExecutorHarness(AgentRunService runService, AgentStepRepository steps) {
    this.runService = runService;
    this.steps = steps;
  }

  /**
   * Walks a {@code QUEUED} run through {@code RUNNING} to {@code COMPLETED}, recording one step per
   * stage so the trace is non-empty and ordered.
   *
   * @return the run in its final state
   */
  AgentRun execute(UUID organizationId, UUID runId) {
    AgentRun run = runService.transition(organizationId, runId, AgentRunStatus.RUNNING, null);

    recordStep(organizationId, runId, 0, AgentStepType.CLASSIFY);
    recordStep(organizationId, runId, 1, AgentStepType.RETRIEVE);
    recordStep(organizationId, runId, 2, AgentStepType.FINALIZE);

    runService.recordUsage(organizationId, runId, 120, 45, 3);
    return runService.transition(organizationId, runId, AgentRunStatus.COMPLETED, null);
  }

  /** Leaves the run waiting on a human, which is where an approval-bearing workflow stops. */
  AgentRun executeUntilApproval(UUID organizationId, UUID runId) {
    runService.transition(organizationId, runId, AgentRunStatus.RUNNING, null);
    recordStep(organizationId, runId, 0, AgentStepType.CLASSIFY);
    recordStep(organizationId, runId, 1, AgentStepType.PROPOSE_TOOL);
    return runService.transition(organizationId, runId, AgentRunStatus.AWAITING_APPROVAL, null);
  }

  private void recordStep(UUID organizationId, UUID runId, int sequence, AgentStepType type) {
    steps.saveAndFlush(
        new AgentStep(organizationId, runId, sequence, type, AgentStepStatus.SUCCEEDED));
  }
}
