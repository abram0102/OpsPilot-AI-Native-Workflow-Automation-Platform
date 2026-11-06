package com.opspilot.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A support ticket. {@code customerId} is a plain column, not a JPA {@code @ManyToOne} — tenant
 * isolation here is enforced primarily by the DB's composite foreign key ({@code
 * fk_tickets_customer_same_org} in V002__tickets.sql), not by an object graph Hibernate has to be
 * trusted to keep consistent. {@link #version} backs optimistic locking (docs/API_CONTRACT.md:
 * stale version -> 409 STATE_CONFLICT, wired up in T10).
 */
@Entity
@Table(name = "tickets")
public class Ticket {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "customer_id", nullable = false)
  private UUID customerId;

  @Column(nullable = false)
  private String subject;

  @Column(nullable = false)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TicketStatus status = TicketStatus.OPEN;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TicketPriority priority = TicketPriority.MEDIUM;

  @Column(name = "assignee_id")
  private UUID assigneeId;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Version
  @Column(nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Ticket() {}

  public Ticket(
      UUID organizationId,
      UUID customerId,
      String subject,
      String description,
      TicketPriority priority,
      UUID createdBy) {
    this.organizationId = organizationId;
    this.customerId = customerId;
    this.subject = subject;
    this.description = description;
    this.priority = priority;
    this.createdBy = createdBy;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public String getSubject() {
    return subject;
  }

  public void setSubject(String subject) {
    this.subject = subject;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public TicketStatus getStatus() {
    return status;
  }

  public void setStatus(TicketStatus status) {
    this.status = status;
  }

  public TicketPriority getPriority() {
    return priority;
  }

  public void setPriority(TicketPriority priority) {
    this.priority = priority;
  }

  public UUID getAssigneeId() {
    return assigneeId;
  }

  public void setAssigneeId(UUID assigneeId) {
    this.assigneeId = assigneeId;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
