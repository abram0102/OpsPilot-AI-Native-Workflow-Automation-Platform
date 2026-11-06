package com.opspilot.ticket;

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

/**
 * One entry in a ticket's internal conversation. The DB CHECK constraint {@code
 * chk_ticket_messages_author} is the real enforcement; the factory methods below exist so
 * application code can't even construct a message that would violate it (a USER message with no
 * author, or an AGENT message with a spurious human author).
 */
@Entity
@Table(name = "ticket_messages")
public class TicketMessage {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "ticket_id", nullable = false)
  private UUID ticketId;

  @Enumerated(EnumType.STRING)
  @Column(name = "author_type", nullable = false)
  private TicketMessageAuthorType authorType;

  @Column(name = "author_id")
  private UUID authorId;

  @Column(nullable = false)
  private String body;

  @Column(name = "published_by")
  private UUID publishedBy;

  @Column(name = "source_run_id")
  private UUID sourceRunId;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected TicketMessage() {}

  private TicketMessage(
      UUID organizationId,
      UUID ticketId,
      TicketMessageAuthorType authorType,
      UUID authorId,
      String body) {
    this.organizationId = organizationId;
    this.ticketId = ticketId;
    this.authorType = authorType;
    this.authorId = authorId;
    this.body = body;
  }

  public static TicketMessage fromUser(
      UUID organizationId, UUID ticketId, UUID authorId, String body) {
    return new TicketMessage(
        organizationId, ticketId, TicketMessageAuthorType.USER, authorId, body);
  }

  /**
   * An agent-authored message. {@code publishedBy}/{@code sourceRunId} are set separately, once the
   * publishing approval actually exists (T22/T23) — this factory alone does not claim a message was
   * published.
   */
  public static TicketMessage fromAgent(UUID organizationId, UUID ticketId, String body) {
    return new TicketMessage(organizationId, ticketId, TicketMessageAuthorType.AGENT, null, body);
  }

  public void markPublished(UUID publishedBy, UUID sourceRunId) {
    this.publishedBy = publishedBy;
    this.sourceRunId = sourceRunId;
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

  public TicketMessageAuthorType getAuthorType() {
    return authorType;
  }

  public UUID getAuthorId() {
    return authorId;
  }

  public String getBody() {
    return body;
  }

  public UUID getPublishedBy() {
    return publishedBy;
  }

  public UUID getSourceRunId() {
    return sourceRunId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
