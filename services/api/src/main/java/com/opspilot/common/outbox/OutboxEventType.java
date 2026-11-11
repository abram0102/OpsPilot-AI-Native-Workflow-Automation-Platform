package com.opspilot.common.outbox;

import java.util.Set;

/**
 * The event types this system publishes (docs/API_CONTRACT.md's envelope {@code type} field).
 *
 * <p>An allowlist rather than documentation: the publisher refuses to put a type it does not
 * recognise onto the stream, because a consumer that cannot route a message would otherwise only
 * discover the problem at the far end of the queue.
 *
 * <p>Note {@code knowledge.ingest.requested}. T13 originally wrote {@code
 * knowledge.document.queued}, which never matched the contract; T16 and T18 both recorded the
 * mismatch for this ticket to settle, and it is settled by renaming what is emitted rather than
 * translating at publish time — a stored row that disagrees with the stream it produced is a
 * debugging trap (docs/DECISIONS.md ADR-048).
 */
public final class OutboxEventType {

  public static final String KNOWLEDGE_INGEST_REQUESTED = "knowledge.ingest.requested";
  public static final String AGENT_RUN_REQUESTED = "agent.run.requested";
  public static final String AGENT_RUN_RESUME = "agent.run.resume";

  public static final Set<String> PUBLISHABLE =
      Set.of(KNOWLEDGE_INGEST_REQUESTED, AGENT_RUN_REQUESTED, AGENT_RUN_RESUME);

  private OutboxEventType() {}

  public static boolean isPublishable(String type) {
    return type != null && PUBLISHABLE.contains(type);
  }
}
