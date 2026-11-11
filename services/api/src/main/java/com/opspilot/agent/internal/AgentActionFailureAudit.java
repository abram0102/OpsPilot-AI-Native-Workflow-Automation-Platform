package com.opspilot.agent.internal;

import com.opspilot.audit.AuditActorType;
import com.opspilot.audit.AuditEvent;
import com.opspilot.audit.AuditEventRepository;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Records a failed action attempt in a transaction of its own (docs/TASKS.md T26).
 *
 * <p>Separate because of something measured rather than assumed. When the business mutation fails
 * against the database, Spring marks the surrounding transaction rollback-only as the exception
 * leaves the repository's boundary, and everything written after that — the {@code
 * FAILED_RETRYABLE} status, the audit row — rolls back with it. A probe confirmed the outcome
 * exactly: nothing published, no execution row, and <em>no record at all</em> that an attempt had
 * been made.
 *
 * <p>The atomicity is correct and is kept: the effect, the execution row and the success audit must
 * commit together or not at all, which is precisely why the failure path cannot share that fate.
 * {@code REQUIRES_NEW} suspends the doomed transaction and commits this one row on its own, so a
 * failure that rolls back is still a failure an operator can see.
 *
 * <p>Nothing here can bring back the <em>effect</em>, and that is the point: the mutation stays
 * rolled back and the worker retries under the same {@code (runId, toolCallId)}.
 */
@Service
class AgentActionFailureAudit {

  private static final Logger log = LoggerFactory.getLogger(AgentActionFailureAudit.class);

  private final AuditEventRepository auditEvents;
  private final ObjectMapper objectMapper;

  AgentActionFailureAudit(AuditEventRepository auditEvents, ObjectMapper objectMapper) {
    this.auditEvents = auditEvents;
    this.objectMapper = objectMapper;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void recordFailure(
      UUID organizationId, UUID runId, UUID ticketId, ExecuteAgentActionRequest request) {
    try {
      auditEvents.save(
          new AuditEvent(
              organizationId,
              AuditActorType.AGENT,
              runId,
              "agent.action.failed",
              "ticket",
              ticketId,
              // Identifiers and codes only — never the exception's message, which may quote the
              // reply body or a database row (docs/SECURITY_AND_TESTING.md).
              objectMapper.writeValueAsString(
                  Map.of(
                      "toolCallId", request.toolCallId(),
                      "actionType", request.actionType().name()))));
    } catch (RuntimeException e) {
      // A failure path must not fail. If even this cannot be written the original failure is still
      // returned to the caller, which is what makes the work get retried.
      log.error(
          "Could not record the failure audit for run {} (toolCallId={}).",
          runId,
          request.toolCallId(),
          e);
    }
  }
}
