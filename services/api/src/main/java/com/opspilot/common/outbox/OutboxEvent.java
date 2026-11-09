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
}
