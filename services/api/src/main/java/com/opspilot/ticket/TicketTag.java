package com.opspilot.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "ticket_tags")
public class TicketTag {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "ticket_id", nullable = false)
  private UUID ticketId;

  @Column(nullable = false)
  private String tag;

  @Column(name = "created_by_run_id")
  private UUID createdByRunId;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected TicketTag() {}

  public TicketTag(UUID organizationId, UUID ticketId, String tag, UUID createdByRunId) {
    this.organizationId = organizationId;
    this.ticketId = ticketId;
    this.tag = tag;
    this.createdByRunId = createdByRunId;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getTicketId() {
    return ticketId;
  }

  public String getTag() {
    return tag;
  }

  public UUID getCreatedByRunId() {
    return createdByRunId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
