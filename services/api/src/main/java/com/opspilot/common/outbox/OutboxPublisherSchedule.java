package com.opspilot.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ticks the publisher in the background (docs/TASKS.md T24).
 *
 * <p>Deliberately separate from {@link OutboxPublisher}, and off unless switched on. A test that
 * had to wait for a timer would be both slow and flaky, so the tests call {@code publishBatch()}
 * directly and assert on its result; this class only answers "who calls it in production".
 *
 * <p>Enabled by {@code app.outbox.publisher.enabled}, which {@code application-local.yml} and
 * production turn on. It stays off in tests, matching how the test issuer and the storage adapters
 * are already gated.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "app.outbox.publisher", name = "enabled", havingValue = "true")
class OutboxPublisherSchedule {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisherSchedule.class);

  private final OutboxPublisher publisher;

  OutboxPublisherSchedule(OutboxPublisher publisher) {
    this.publisher = publisher;
  }

  /**
   * Fixed delay rather than fixed rate: a slow or failing Redis must not let ticks pile up on top
   * of each other, each claiming rows the previous one is still working through.
   */
  @Scheduled(
      fixedDelayString = "${app.outbox.publisher.interval-ms}",
      initialDelayString = "${app.outbox.publisher.interval-ms}")
  void publishPending() {
    try {
      int published = publisher.publishBatch();
      if (published > 0) {
        log.debug("Published {} outbox event(s).", published);
      }
    } catch (RuntimeException e) {
      // A scheduled method that throws is silently never rescheduled by some executors; the
      // backlog would then stall with no signal at all. Log and let the next tick try.
      log.error("Outbox publishing tick failed.", e);
    }
  }
}
