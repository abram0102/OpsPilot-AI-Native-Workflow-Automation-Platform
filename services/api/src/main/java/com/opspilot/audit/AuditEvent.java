package com.opspilot.audit;

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
 * An append-only record of something that happened (docs/DATA_MODEL.md section A).
 *
 * <p>Append-only is enforced by having no mutator at all: there is no setter, and nothing in this
 * codebase updates or deletes one. {@code actorId} is deliberately not a foreign key — an audit row
 * has to outlive the user or run it describes, and a cascade would erase exactly the history an
 * audit trail exists to keep.
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "actor_type", nullable = false)
  private AuditActorType actorType;

  @Column(name = "actor_id")
  private UUID actorId;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @Column(name = "target_type", nullable = false)
  private String targetType;

  @Column(name = "target_id")
  private UUID targetId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String metadata = "{}";

  @CreationTimestamp
  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  protected AuditEvent() {}

  public AuditEvent(
      UUID organizationId,
      AuditActorType actorType,
      UUID actorId,
      String eventType,
      String targetType,
      UUID targetId,
      String metadata) {
    this.organizationId = organizationId;
    this.actorType = actorType;
    this.actorId = actorId;
    this.eventType = eventType;
    this.targetType = targetType;
    this.targetId = targetId;
    this.metadata = metadata == null ? "{}" : metadata;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public AuditActorType getActorType() {
    return actorType;
  }

  public UUID getActorId() {
    return actorId;
  }

  public String getEventType() {
    return eventType;
  }

  public String getTargetType() {
    return targetType;
  }

  public UUID getTargetId() {
    return targetId;
  }

  public String getMetadata() {
    return metadata;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
