package com.opspilot.common.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Moves committed outbox rows onto {@code opspilot:events} (docs/TASKS.md T24).
 *
 * <p>Publication is a *separate* transaction from the business change that wrote the row — that
 * separation is the whole point of an outbox (docs/DATA_MODEL.md invariant 7). The business write
 * is already durable before this runs, so Redis being slow, broken or absent can never fail a
 * customer's request; it only delays delivery.
 *
 * <p><strong>Delivery is at-least-once, and the duplicate is deliberate.</strong> The stream write
 * happens before the row is marked published, so a crash in between republishes the event with the
 * same {@code eventId}. The alternative ordering — mark first, publish second — would lose events
 * instead, and a lost event is unrecoverable while a duplicate is merely work the consumer must
 * de-duplicate. docs/DECISIONS.md ADR-002 does not claim exactly-once delivery; T26 is where the
 * consumer-side effect is proven singular.
 */
@Component
public class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  /** The envelope is written as a single stream field; the consumer parses one JSON document. */
  static final String PAYLOAD_FIELD = "envelope";

  private final OutboxEventRepository outboxEvents;
  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final String streamKey;
  private final int batchSize;
  private final int maxAttempts;
  private final Duration backoffBase;

  public OutboxPublisher(
      OutboxEventRepository outboxEvents,
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      @Value("${app.outbox.stream-key}") String streamKey,
      @Value("${app.outbox.batch-size}") int batchSize,
      @Value("${app.outbox.max-attempts}") int maxAttempts,
      @Value("${app.outbox.backoff-base}") Duration backoffBase) {
    this.outboxEvents = outboxEvents;
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.streamKey = streamKey;
    this.batchSize = batchSize;
    this.maxAttempts = maxAttempts;
    this.backoffBase = backoffBase;
  }

  /**
   * Claims and publishes one batch, returning how many reached the stream.
   *
   * <p>Public and directly callable so tests drive it rather than waiting on a timer — a scheduled
   * trigger lives in {@link OutboxPublisherSchedule}, which is off unless enabled.
   */
  @Transactional
  public int publishBatch() {
    Instant now = Instant.now();
    List<OutboxEvent> claimed = outboxEvents.claimPublishable(now, batchSize);
    if (claimed.isEmpty()) {
      return 0;
    }

    int published = 0;
    for (OutboxEvent event : claimed) {
      if (publish(event, now)) {
        published++;
      }
    }
    return published;
  }

  private boolean publish(OutboxEvent event, Instant now) {
    OutboxEventEnvelope envelope = OutboxEventEnvelope.of(event);

    OutboxEnvelopeVerdict verdict = OutboxEnvelopeVerdict.classify(envelope);
    if (!verdict.isAccepted()) {
      // Nothing on the far end could route this, and no amount of retrying changes it. Dead, but
      // still visible: the row is kept so an operator can see what was rejected and why.
      log.error(
          "Refusing to publish outbox event {} ({}): {}",
          event.getId(),
          event.getEventType(),
          verdict);
      event.markDead();
      outboxEvents.save(event);
      return false;
    }

    try {
      Map<String, String> body = new HashMap<>();
      body.put(PAYLOAD_FIELD, objectMapper.writeValueAsString(envelope));
      redis.opsForStream().add(StreamRecords.mapBacked(body).withStreamKey(streamKey));
    } catch (RuntimeException e) {
      // Caught broadly on purpose, and narrow in scope: the only statements guarded are the
      // serialization and the stream write, and every outcome is *recorded on the row* rather
      // than swallowed. Spring's Redis failures arrive as DataAccessException and Jackson 3's as
      // JacksonException, both unchecked, and a publisher that died on an unanticipated third
      // kind would stall the whole backlog behind one bad row.
      // Redis unavailable, or the write failed. The row stays PENDING with a later next_attempt_at,
      // so the backlog survives and drains when Redis returns — the ticket's "outbox stays durable
      // when Redis unavailable".
      return retryLater(event, e);
    }

    // Only now. If the process dies here the event is republished with the same eventId, which is
    // the documented duplication risk rather than a bug.
    event.markPublished(now);
    outboxEvents.save(event);
    return true;
  }

  private boolean retryLater(OutboxEvent event, RuntimeException cause) {
    event.recordFailedAttempt(nextAttemptAfter(event.getAttempts()));

    if (event.getAttempts() >= maxAttempts) {
      log.error(
          "Outbox event {} failed {} times; marking DEAD.",
          event.getId(),
          event.getAttempts(),
          cause);
      event.markDead();
    } else {
      log.warn(
          "Could not publish outbox event {} (attempt {}); will retry.",
          event.getId(),
          event.getAttempts(),
          cause);
    }

    outboxEvents.save(event);
    return false;
  }

  /** Bounded exponential backoff, so a long Redis outage does not become a tight retry loop. */
  private Instant nextAttemptAfter(int attempts) {
    long multiplier = 1L << Math.min(attempts, 6);
    return Instant.now().plus(backoffBase.multipliedBy(multiplier));
  }
}
