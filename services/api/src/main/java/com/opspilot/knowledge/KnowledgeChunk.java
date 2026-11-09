package com.opspilot.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One embedded chunk of a knowledge document. Not populated until T16 (ingestion) — this entity
 * exists now because V003 (T13) is this schema's one knowledge-migration slot (docs/DATA_MODEL.md
 * section D), the same forward-schema precedent T06 set for {@code ticket_tags.created_by_run_id}.
 * {@code embedding} is deliberately untyped here ({@code Object}) — no entity field maps {@code
 * pgvector}'s {@code vector} column until the ingestion ticket actually needs to read/write it, so
 * this class makes no unverified claim about pgvector's JPA mapping.
 */
@Entity
@Table(name = "knowledge_chunks")
public class KnowledgeChunk {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "document_id", nullable = false)
  private UUID documentId;

  @Column(name = "chunk_index", nullable = false)
  private int chunkIndex;

  @Column(name = "chunk_text", nullable = false)
  private String chunkText;

  @Column(name = "embedding_model")
  private String embeddingModel;

  @Column(name = "token_count")
  private Integer tokenCount;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected KnowledgeChunk() {}

  public KnowledgeChunk(UUID organizationId, UUID documentId, int chunkIndex, String chunkText) {
    this.organizationId = organizationId;
    this.documentId = documentId;
    this.chunkIndex = chunkIndex;
    this.chunkText = chunkText;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public int getChunkIndex() {
    return chunkIndex;
  }

  public String getChunkText() {
    return chunkText;
  }

  public String getEmbeddingModel() {
    return embeddingModel;
  }

  public Integer getTokenCount() {
    return tokenCount;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
