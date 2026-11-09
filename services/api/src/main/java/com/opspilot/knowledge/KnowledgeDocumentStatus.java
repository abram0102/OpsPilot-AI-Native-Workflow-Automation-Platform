package com.opspilot.knowledge;

/**
 * docs/DATA_MODEL.md section C: {@code UPLOADING -> QUEUED -> PROCESSING -> READY} or {@code
 * FAILED}; retries {@code FAILED -> QUEUED}. T13 only ever reaches {@code UPLOADING} and {@code
 * QUEUED} — {@code PROCESSING}/{@code READY}/{@code FAILED} are T16's (ingestion) job.
 */
public enum KnowledgeDocumentStatus {
  UPLOADING,
  QUEUED,
  PROCESSING,
  READY,
  FAILED
}
