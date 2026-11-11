package com.opspilot.common.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The transactional outbox row (docs/DATA_MODEL.md invariant #7: "inserted *in the same
 * transaction* as the business change"). Only the insert is this project's job today — a publisher
 * that actually dispatches these to Redis Streams is T24/T25's job, not T13's; nothing reads {@code
 * status}/{@code attempts}/{@code next_attempt_at} yet. {@code payload} is pre-serialized JSON text
 * (caller's responsibility), mapped to the {@code jsonb} column via Hibernate's built-in
 * {@code @JdbcTypeCode(SqlTypes.JSON)} — no extra library needed.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @Column(name = "aggregate_type", nullable = false)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String payload;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private OutboxEventStatus status = OutboxEventStatus.PENDING;

  @Column(nullable = false)
  private int attempts = 0;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected OutboxEvent() {}

  public OutboxEvent(
      String eventType,
      String aggregateType,
      UUID aggregateId,
      UUID organizationId,
      String payload) {
    this.eventType = eventType;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.organizationId = organizationId;
    this.payload = payload;
  }

  public UUID getId() {
    return id;
  }

  public String getEventType() {
    return eventType;
  }

  public String getAggregateType() {
    return aggregateType;
  }

  public UUID getAggregateId() {
    return aggregateId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getPayload() {
    return payload;
  }

  public OutboxEventStatus getStatus() {
    return status;
  }

  public int getAttempts() {
    return attempts;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  /**
   * Marks the row published. Terminal: a published event is never republished from this row.
   *
   * <p>Note the ordering this cannot protect against — the stream write happens first, so a crash
   * between `XADD` and this update leaves the row PENDING and the event is published again with the
   * same {@code eventId}. That duplicate is by design (docs/DECISIONS.md ADR-048); what must never
   * happen is a *lost* event, which is why the update follows the publish rather than preceding it.
   */
  void markPublished(Instant at) {
    this.status = OutboxEventStatus.PUBLISHED;
    this.publishedAt = at;
    this.nextAttemptAt = null;
  }

  /** Records a failed publish and when to try again. */
  void recordFailedAttempt(Instant nextAttemptAt) {
    this.attempts += 1;
    this.nextAttemptAt = nextAttemptAt;
  }

  /**
   * Gives up on the row, leaving it visible rather than silently stuck.
   *
   * <p>docs/SECURITY_AND_TESTING.md: "poison message -> DLQ or terminal failure with
   * operator-visible reason; no endless tight retry loop." DEAD is that terminal state on the
   * publisher side; it is deliberately not deleted, so an operator can still see what failed.
   */
  void markDead() {
    this.status = OutboxEventStatus.DEAD;
    this.nextAttemptAt = null;
  }
}
