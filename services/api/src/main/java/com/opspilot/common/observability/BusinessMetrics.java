package com.opspilot.common.observability;

import com.opspilot.agent.AgentRunRepository;
import com.opspilot.agent.AgentRunStatus;
import com.opspilot.agent.ApprovalRepository;
import com.opspilot.agent.ApprovalStatus;
import com.opspilot.common.outbox.OutboxEventRepository;
import com.opspilot.common.outbox.OutboxEventStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The business metrics docs/SECURITY_AND_TESTING.md §7 names: run status distribution, approval
 * age, and dead-letter count (docs/TASKS.md T30).
 *
 * <p>Micrometer already gives request rate, p50/p95 and 5xx counts for free through {@code
 * http.server.requests} — nothing here needs to reinvent those, and anything that did would be a
 * second, disagreeing source of truth. What it cannot know is what the numbers in this database
 * mean, which is all this class adds.
 *
 * <p><b>Gauges, not counters.</b> Every value here answers "how many right now", and a gauge
 * re-reads on scrape. A counter would have to be incremented at every write site, which means every
 * new code path is a chance to forget one, and a missed increment is invisible — the metric just
 * reads low forever. Reading the database instead cannot drift from it.
 *
 * <p><b>Cached, because a gauge is polled.</b> Prometheus scrapes on its own schedule, and a
 * careless implementation would issue a COUNT per metric per scrape — including from a scraper
 * misconfigured to poll every second. Each value is therefore cached for {@link #CACHE_TTL}; a
 * fifteen-second-stale queue depth is a perfectly good operational signal, and a database melting
 * under its own monitoring is not.
 *
 * <p><b>Approval age is the oldest pending one</b>, not a mean. The question an operator has is "is
 * anything waiting too long", and a mean hides exactly that: nineteen fresh approvals and one
 * three-day-old one average out to something that looks fine.
 */
@Component
public class BusinessMetrics {

  // No @ConditionalOnBean(MeterRegistry.class). That annotation is evaluated in bean-definition
  // order and is only reliable inside auto-configuration — used here it silently excluded this
  // bean, and the first run of MetricsEndpointIntegrationTest scraped a perfectly healthy
  // endpoint carrying none of these metrics. Injecting the registry directly means a missing one
  // is a startup failure, which is the honest outcome: a service that cannot report its own
  // metrics should say so rather than appear to run while reporting nothing.

  private static final Logger log = LoggerFactory.getLogger(BusinessMetrics.class);

  /** Long enough that a scraper cannot hammer the database, short enough to be an alert signal. */
  static final Duration CACHE_TTL = Duration.ofSeconds(15);

  private final AgentRunRepository runs;
  private final ApprovalRepository approvals;
  private final OutboxEventRepository outbox;

  private final Cached<java.util.Map<AgentRunStatus, Long>> runCounts;
  private final Cached<Double> oldestPendingApprovalSeconds;
  private final Cached<Double> deadLetteredEvents;
  private final Cached<Double> pendingOutboxEvents;

  public BusinessMetrics(
      MeterRegistry registry,
      AgentRunRepository runs,
      ApprovalRepository approvals,
      OutboxEventRepository outbox) {
    this.runs = runs;
    this.approvals = approvals;
    this.outbox = outbox;

    this.runCounts = new Cached<>(this::readRunCounts, java.util.Map.of());
    this.oldestPendingApprovalSeconds = new Cached<>(this::readOldestPendingApprovalSeconds, 0.0);
    this.deadLetteredEvents = new Cached<>(this::readDeadLettered, 0.0);
    this.pendingOutboxEvents = new Cached<>(this::readPendingOutbox, 0.0);

    // One series per status rather than one metric per status: a dashboard can then sum, stack or
    // filter by the tag, and a status added later needs no new metric name.
    for (AgentRunStatus status : AgentRunStatus.values()) {
      registry.gauge(
          "opspilot.agent.runs",
          Tags.of("status", status.name()),
          this,
          metrics -> metrics.runCounts.get().getOrDefault(status, 0L).doubleValue());
    }

    registry.gauge(
        "opspilot.approvals.oldest.pending.seconds",
        this,
        metrics -> metrics.oldestPendingApprovalSeconds.get());

    registry.gauge(
        "opspilot.outbox.events",
        Tags.of("status", OutboxEventStatus.PENDING.name()),
        this,
        metrics -> metrics.pendingOutboxEvents.get());

    registry.gauge(
        "opspilot.outbox.events",
        Tags.of("status", OutboxEventStatus.DEAD.name()),
        this,
        metrics -> metrics.deadLetteredEvents.get());
  }

  @Transactional(readOnly = true)
  java.util.Map<AgentRunStatus, Long> readRunCounts() {
    java.util.Map<AgentRunStatus, Long> counts = new java.util.EnumMap<>(AgentRunStatus.class);
    for (AgentRunStatus status : AgentRunStatus.values()) {
      counts.put(status, runs.countByStatus(status));
    }
    return counts;
  }

  @Transactional(readOnly = true)
  Double readOldestPendingApprovalSeconds() {
    Instant oldest = approvals.oldestCreatedAtByStatus(ApprovalStatus.PENDING);
    // Zero when nothing is pending — the honest reading of "nothing has been waiting any time at
    // all", and the value an alert threshold should sit above.
    return oldest == null ? 0.0 : (double) Duration.between(oldest, Instant.now()).toSeconds();
  }

  @Transactional(readOnly = true)
  Double readDeadLettered() {
    return (double) outbox.countByStatus(OutboxEventStatus.DEAD);
  }

  @Transactional(readOnly = true)
  Double readPendingOutbox() {
    return (double) outbox.countByStatus(OutboxEventStatus.PENDING);
  }

  /**
   * A value recomputed at most once per {@link #CACHE_TTL}, falling back to the last good reading.
   *
   * <p>A metric read must never be able to take the application down with it. If the database is
   * unreachable the gauge reports its previous value and logs once at debug — an exception thrown
   * out of a scrape would otherwise surface as a failing /actuator/prometheus, which an operator
   * would reasonably read as "the service is broken" when the truth is "monitoring is broken".
   */
  private final class Cached<T> {
    private final java.util.function.Supplier<T> source;
    private final AtomicReference<T> value;
    private volatile Instant readAt = Instant.EPOCH;

    private Cached(java.util.function.Supplier<T> source, T initial) {
      this.source = source;
      this.value = new AtomicReference<>(initial);
    }

    T get() {
      Instant now = Instant.now();
      if (Duration.between(readAt, now).compareTo(CACHE_TTL) < 0) {
        return value.get();
      }
      try {
        value.set(source.get());
      } catch (RuntimeException e) {
        log.debug("Business metric read failed; reporting the previous value.", e);
      }
      readAt = now;
      return value.get();
    }
  }
}
