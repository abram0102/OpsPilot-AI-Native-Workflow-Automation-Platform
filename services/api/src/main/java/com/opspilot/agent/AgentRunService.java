package com.opspilot.agent;

import com.opspilot.common.web.ConflictException;
import com.opspilot.common.web.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The run state machine and its lease (docs/DATA_MODEL.md section C; docs/TASKS.md T17).
 *
 * <p>This is a persistence-layer service with no HTTP surface. T17 deliberately adds no endpoint:
 * every run endpoint belongs to T18, nothing can create a run until then, and an endpoint over
 * tables nothing populates would be the empty placeholder AGENTS.md rule 7 forbids. The acceptance
 * criterion is that invalid transitions are rejected *by the service*, which is what this enforces.
 */
@Service
public class AgentRunService {

  /** docs/DATA_MODEL.md section C, as a table rather than scattered conditionals. */
  private static final Map<AgentRunStatus, Set<AgentRunStatus>> ALLOWED_TRANSITIONS =
      new EnumMap<>(
          Map.of(
              AgentRunStatus.QUEUED,
                  EnumSet.of(
                      AgentRunStatus.RUNNING,
                      AgentRunStatus.RETRY_SCHEDULED,
                      AgentRunStatus.CANCELLED,
                      AgentRunStatus.FAILED),
              AgentRunStatus.RUNNING,
                  EnumSet.of(
                      AgentRunStatus.AWAITING_APPROVAL,
                      AgentRunStatus.RETRY_SCHEDULED,
                      AgentRunStatus.COMPLETED,
                      AgentRunStatus.FAILED,
                      // "Running cancellation is cooperative" — the worker observes the request
                      // and stops; the status may move straight to CANCELLED.
                      AgentRunStatus.CANCELLED),
              AgentRunStatus.AWAITING_APPROVAL,
                  EnumSet.of(
                      AgentRunStatus.RUNNING,
                      // A rejected proposal ends the run as COMPLETED, not FAILED — see ADR-041.
                      AgentRunStatus.COMPLETED,
                      AgentRunStatus.CANCELLED,
                      AgentRunStatus.FAILED),
              AgentRunStatus.RETRY_SCHEDULED,
                  EnumSet.of(
                      AgentRunStatus.QUEUED, AgentRunStatus.FAILED, AgentRunStatus.CANCELLED),
              AgentRunStatus.COMPLETED, EnumSet.noneOf(AgentRunStatus.class),
              AgentRunStatus.FAILED, EnumSet.noneOf(AgentRunStatus.class),
              AgentRunStatus.CANCELLED, EnumSet.noneOf(AgentRunStatus.class)));

  private final AgentRunRepository runs;

  public AgentRunService(AgentRunRepository runs) {
    this.runs = runs;
  }

  @Transactional(readOnly = true)
  public AgentRun require(UUID organizationId, UUID runId) {
    return runs.findByIdAndOrganizationId(runId, organizationId)
        .orElseThrow(() -> new NotFoundException("No such run."));
  }

  /**
   * Moves a run to {@code next}, or refuses. Terminal states never move again, so a completed run
   * cannot be resurrected by a late message.
   */
  @Transactional
  public AgentRun transition(
      UUID organizationId, UUID runId, AgentRunStatus next, String errorCode) {
    AgentRun run = require(organizationId, runId);
    AgentRunStatus current = run.getStatus();

    if (!ALLOWED_TRANSITIONS.get(current).contains(next)) {
      throw new ConflictException("Cannot move a run from " + current + " to " + next + ".");
    }

    run.applyStatus(next, errorCode);
    if (next == AgentRunStatus.RUNNING) {
      run.recordAttempt();
    }
    if (next.isTerminal()) {
      // A finished run holds nothing: leaving a lease behind would keep it out of the reclaimer's
      // view forever for no reason.
      run.applyLease(null, null);
    }
    return runs.saveAndFlush(run);
  }

  /**
   * Takes an expiring lease, so a worker that dies does not strand the run.
   *
   * <p>Refuses while someone else's lease is still live. That check plus the row lock is what stops
   * two workers running the same ticket; the lease expiry is what makes a crash recoverable without
   * an operator.
   */
  @Transactional
  public AgentRun claimLease(UUID organizationId, UUID runId, String owner, Duration ttl) {
    AgentRun run = require(organizationId, runId);

    if (run.getStatus().isTerminal()) {
      throw new ConflictException("Cannot lease a run that is already " + run.getStatus() + ".");
    }
    Instant now = Instant.now();
    if (run.isLeasedAt(now) && !owner.equals(run.getLeaseOwner())) {
      throw new ConflictException(
          "This run is leased by another worker until " + run.getLeaseExpiresAt() + ".");
    }

    run.applyLease(owner, now.plus(ttl));
    return runs.saveAndFlush(run);
  }

  /** Gives the lease back early, making the run immediately claimable again. */
  @Transactional
  public AgentRun releaseLease(UUID organizationId, UUID runId, String owner) {
    AgentRun run = require(organizationId, runId);

    if (run.getLeaseOwner() != null && !run.getLeaseOwner().equals(owner)) {
      throw new ConflictException("This run is leased by another worker.");
    }
    run.applyLease(null, null);
    return runs.saveAndFlush(run);
  }

  /** Runs nothing currently holds: unleased or expired, and not finished. */
  @Transactional(readOnly = true)
  public List<AgentRun> findClaimable(Instant now) {
    return runs.findClaimable(
        List.of(
            AgentRunStatus.QUEUED,
            AgentRunStatus.RUNNING,
            AgentRunStatus.AWAITING_APPROVAL,
            AgentRunStatus.RETRY_SCHEDULED),
        now);
  }

  @Transactional
  public AgentRun recordUsage(
      UUID organizationId, UUID runId, int inputTokens, int outputTokens, int stepsUsed) {
    AgentRun run = require(organizationId, runId);
    run.recordUsage(inputTokens, outputTokens, stepsUsed);
    return runs.saveAndFlush(run);
  }

  @Transactional
  public AgentRun assignCheckpointThread(UUID organizationId, UUID runId, UUID threadId) {
    AgentRun run = require(organizationId, runId);
    run.assignCheckpointThread(threadId);
    return runs.saveAndFlush(run);
  }
}
