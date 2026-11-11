package com.opspilot.common.outbox;

/**
 * Whether an envelope may be acted on (docs/TASKS.md T24's "event schema/version tests").
 *
 * <p>A verdict rather than a boolean, and rather than an exception, because the three cases lead
 * somewhere different: one is published, one is a deployment-generation mismatch an operator must
 * see, and one is a bug. T25 maps the two rejecting verdicts onto the dead-letter stream; this
 * ticket only has to classify them correctly and prove it does.
 */
public enum OutboxEnvelopeVerdict {
  ACCEPTED,

  /**
   * A version this build does not understand — including a missing one, which deserializes to zero.
   * Retrying cannot help: the message will not get newer.
   */
  UNKNOWN_SCHEMA_VERSION,

  /** Structurally wrong: a required identifier or the type is absent. */
  MALFORMED,

  /** Well-formed, but naming an event type nothing routes. */
  UNKNOWN_TYPE;

  public boolean isAccepted() {
    return this == ACCEPTED;
  }

  /** Classifies an envelope exactly as a consumer should, so both sides agree on the rules. */
  public static OutboxEnvelopeVerdict classify(OutboxEventEnvelope envelope) {
    if (envelope == null
        || envelope.eventId() == null
        || envelope.aggregateId() == null
        || envelope.organizationId() == null
        || envelope.type() == null
        || envelope.type().isBlank()) {
      return MALFORMED;
    }
    if (envelope.schemaVersion() != OutboxEventEnvelope.SCHEMA_VERSION) {
      return UNKNOWN_SCHEMA_VERSION;
    }
    if (!OutboxEventType.isPublishable(envelope.type())) {
      return UNKNOWN_TYPE;
    }
    return ACCEPTED;
  }
}
