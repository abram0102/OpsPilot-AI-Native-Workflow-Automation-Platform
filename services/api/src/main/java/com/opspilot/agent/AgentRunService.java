package com.opspilot.agent;

import com.opspilot.auth.AuthenticatedPrincipal;
import com.opspilot.auth.OrganizationAccessService;
import com.opspilot.auth.OrganizationAction;
import com.opspilot.common.outbox.OutboxEvent;
import com.opspilot.common.outbox.OutboxEventRepository;
import com.opspilot.common.web.BadRequestException;
import com.opspilot.common.web.ConflictException;
import com.opspilot.common.web.IdempotencyConflictException;
import com.opspilot.common.web.NotFoundException;
import com.opspilot.organization.MembershipRole;
import com.opspilot.ticket.Ticket;
import com.opspilot.ticket.TicketRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

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

  static final int DEFAULT_PAGE_SIZE = 25;
  static final int MAX_PAGE_SIZE = 100;

  /** docs/API_CONTRACT.md's Redis envelope names this type; T24 publishes it, T25 consumes it. */
  private static final String RUN_REQUESTED_EVENT = "agent.run.requested";

  private final AgentRunRepository runs;
  private final AgentStepRepository steps;
  private final TicketRepository tickets;
  private final OutboxEventRepository outboxEvents;
  private final OrganizationAccessService accessService;
  private final ObjectMapper objectMapper;

  public AgentRunService(
      AgentRunRepository runs,
      AgentStepRepository steps,
      TicketRepository tickets,
      OutboxEventRepository outboxEvents,
      OrganizationAccessService accessService,
      ObjectMapper objectMapper) {
    this.runs = runs;
    this.steps = steps;
    this.tickets = tickets;
    this.outboxEvents = outboxEvents;
    this.accessService = accessService;
    this.objectMapper = objectMapper;
  }

  /**
   * Accepts a run request: creates the {@code QUEUED} row and its outbox event in one transaction.
   *
   * <p>Both or neither — a queued run nobody is told about, or an event pointing at no run, are
   * each worse than failing. docs/PRODUCT_AND_ARCHITECTURE.md requires the insert "in one DB
   * transaction", and {@code @Transactional} here is what makes that true.
   *
   * <p>Nothing publishes the event yet (T24) and nothing consumes it (T25), so this method
   * deliberately does not claim the run will start — it records that it was requested.
   */
  @Transactional
  public AgentRun start(
      AuthenticatedPrincipal principal,
      UUID organizationId,
      UUID ticketId,
      Workflow workflow,
      String idempotencyKey) {
    accessService.requireAction(principal, organizationId, OrganizationAction.RUN_AGENT);

    // Tenant-scoped, so a ticket id from another organization reads as absent rather than denied.
    Ticket ticket =
        tickets
            .findByIdAndOrganizationId(ticketId, organizationId)
            .orElseThrow(() -> new NotFoundException("Ticket not found."));

    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      Optional<AgentRun> existing =
          runs.findByOrganizationIdAndIdempotencyKey(organizationId, idempotencyKey);
      if (existing.isPresent()) {
        AgentRun run = existing.get();
        // Same key, same request: hand back the original run. A double-clicked button and a
        // retried POST both land here, and neither should start a second run.
        if (run.getTicketId().equals(ticketId) && run.getWorkflow() == workflow) {
          return run;
        }
        // Same key, different request: the client has reused a key for something else, which is
        // a mistake worth telling them about rather than guessing at.
        throw new IdempotencyConflictException(
            "This Idempotency-Key was already used for a different run request.");
      }
    }

    AgentRun run =
        runs.saveAndFlush(
            new AgentRun(
                organizationId, ticket.getId(), principal.userId(), workflow, idempotencyKey));

    String payload =
        objectMapper.writeValueAsString(
            Map.of(
                "runId", run.getId().toString(),
                "organizationId", organizationId.toString(),
                "ticketId", ticket.getId().toString(),
                "workflow", workflow.name()));
    outboxEvents.save(
        new OutboxEvent(RUN_REQUESTED_EVENT, "agent_run", run.getId(), organizationId, payload));

    return run;
  }

  @Transactional(readOnly = true)
  public AgentRun get(AuthenticatedPrincipal principal, UUID organizationId, UUID runId) {
    accessService.requireAction(principal, organizationId, OrganizationAction.VIEW_RUNS);
    return require(organizationId, runId);
  }

  @Transactional(readOnly = true)
  public AgentRunPageResponse list(
      AuthenticatedPrincipal principal,
      UUID organizationId,
      AgentRunStatus status,
      String cursor,
      Integer limit) {
    accessService.requireAction(principal, organizationId, OrganizationAction.VIEW_RUNS);

    int pageSize = normalizePageSize(limit);
    // One extra row answers "is there another page?" without a second count query.
    Pageable window = PageRequest.of(0, pageSize + 1);

    List<AgentRun> rows;
    if (cursor == null || cursor.isBlank()) {
      rows = runs.findFirstPage(organizationId, status, window);
    } else {
      AgentRunCursors.Decoded decoded = AgentRunCursors.decode(cursor);
      rows = runs.findPageAfter(organizationId, status, decoded.createdAt(), decoded.id(), window);
    }

    boolean hasMore = rows.size() > pageSize;
    List<AgentRun> page = hasMore ? rows.subList(0, pageSize) : rows;
    String nextCursor =
        hasMore
            ? AgentRunCursors.encode(
                page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getId())
            : null;

    return new AgentRunPageResponse(page.stream().map(AgentRunResponse::from).toList(), nextCursor);
  }

  @Transactional(readOnly = true)
  public AgentStepPageResponse listSteps(
      AuthenticatedPrincipal principal,
      UUID organizationId,
      UUID runId,
      String cursor,
      Integer limit) {
    accessService.requireAction(principal, organizationId, OrganizationAction.VIEW_RUNS);
    require(organizationId, runId);

    int pageSize = normalizePageSize(limit);
    int afterSequence = parseStepCursor(cursor);

    List<AgentStep> rows =
        steps.findAllByRunIdAndOrganizationIdAndSequenceGreaterThanOrderBySequence(
            runId, organizationId, afterSequence, PageRequest.of(0, pageSize + 1));

    boolean hasMore = rows.size() > pageSize;
    List<AgentStep> page = hasMore ? rows.subList(0, pageSize) : rows;
    String nextCursor = hasMore ? String.valueOf(page.get(page.size() - 1).getSequence()) : null;

    return new AgentStepPageResponse(
        page.stream().map(AgentStepResponse::from).toList(), nextCursor);
  }

  /**
   * Cancels a run.
   *
   * <p>docs/API_CONTRACT.md gives this to "owner/admin/member initiator": a MEMBER may cancel a run
   * they started, ADMIN and above may cancel any. The first initiator-scoped check in this codebase
   * — see docs/DECISIONS.md ADR-042.
   */
  @Transactional
  public AgentRun cancel(AuthenticatedPrincipal principal, UUID organizationId, UUID runId) {
    accessService.requireAction(principal, organizationId, OrganizationAction.RUN_AGENT);
    MembershipRole role = accessService.requireMembership(principal, organizationId);

    AgentRun run = require(organizationId, runId);
    boolean isInitiator = run.getInitiatedBy().equals(principal.userId());
    boolean canCancelAnyRun = role.compareTo(MembershipRole.ADMIN) >= 0;
    if (!isInitiator && !canCancelAnyRun) {
      throw new AccessDeniedException("You can only cancel runs you started.");
    }

    return transition(organizationId, runId, AgentRunStatus.CANCELLED, null);
  }

  private static int normalizePageSize(Integer requested) {
    if (requested == null) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.max(1, Math.min(requested, MAX_PAGE_SIZE));
  }

  /** Step cursors are just the last sequence seen; {@code -1} means "from the beginning". */
  private static int parseStepCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return -1;
    }
    try {
      return Integer.parseInt(cursor.trim());
    } catch (NumberFormatException e) {
      throw new BadRequestException("Invalid pagination cursor.");
    }
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
