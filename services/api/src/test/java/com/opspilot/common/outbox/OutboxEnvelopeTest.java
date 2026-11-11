package com.opspilot.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T24 acceptance: "event schema/version tests".
 *
 * <p>No database and no Redis — classification is a pure function, and the rules it encodes are the
 * contract between this publisher and the consumer T25 builds. Keeping it pure is what lets the
 * same rules be asserted here without a container, so a broken allowlist fails in seconds on a
 * laptop rather than only in CI.
 */
class OutboxEnvelopeTest {

  private static final UUID EVENT_ID = UUID.randomUUID();
  private static final UUID AGGREGATE_ID = UUID.randomUUID();
  private static final UUID ORG_ID = UUID.randomUUID();

  @Test
  void theContractsSchemaVersionIsOne() {
    // docs/API_CONTRACT.md shows "schemaVersion": 1 in its example envelope. Pinned by a test so
    // that bumping it is a deliberate, visible act rather than a silent incompatibility.
    assertThat(OutboxEventEnvelope.SCHEMA_VERSION).isEqualTo(1);
  }

  @Test
  void everyPublishableTypeIsAccepted() {
    for (String type : OutboxEventType.PUBLISHABLE) {
      assertThat(OutboxEnvelopeVerdict.classify(envelope(type, OutboxEventEnvelope.SCHEMA_VERSION)))
          .as("type %s", type)
          .isEqualTo(OutboxEnvelopeVerdict.ACCEPTED);
    }
  }

  @Test
  void theAllowlistIsExactlyTheThreeContractedTypes() {
    // Not a tautology: it fails if a future ticket adds a constant without deciding whether the
    // consumer can route it. docs/API_CONTRACT.md names these three streams' event types.
    assertThat(OutboxEventType.PUBLISHABLE)
        .containsExactlyInAnyOrder(
            "knowledge.ingest.requested", "agent.run.requested", "agent.run.resume");
  }

  @Test
  void theRenamedKnowledgeTypeMatchesTheContractNotTheOldRowValue() {
    // The mismatch T16 and T18 recorded for this ticket. Asserted so the old name cannot creep
    // back in: a row saying knowledge.document.queued would be rejected by the publisher now.
    assertThat(OutboxEventType.KNOWLEDGE_INGEST_REQUESTED).isEqualTo("knowledge.ingest.requested");
    assertThat(OutboxEventType.isPublishable("knowledge.document.queued")).isFalse();
  }

  @Test
  void anUnknownTypeIsNotPublishable() {
    assertThat(OutboxEnvelopeVerdict.classify(envelope("ticket.deleted.everything", 1)))
        .isEqualTo(OutboxEnvelopeVerdict.UNKNOWN_TYPE);
  }

  @Test
  void aNewerSchemaVersionIsRefusedRatherThanGuessedAt() {
    assertThat(envelopeVerdictAtVersion(OutboxEventEnvelope.SCHEMA_VERSION + 1))
        .isEqualTo(OutboxEnvelopeVerdict.UNKNOWN_SCHEMA_VERSION);
  }

  @Test
  void aMissingSchemaVersionDeserializesToZeroAndIsRefused() {
    // An absent JSON field becomes 0 on an int record component, which must not be mistaken for
    // "version 0 is fine" — the reason the check is `!= SCHEMA_VERSION` rather than `<`.
    assertThat(envelopeVerdictAtVersion(0)).isEqualTo(OutboxEnvelopeVerdict.UNKNOWN_SCHEMA_VERSION);
  }

  @Test
  void aVersionCheckDoesNotMaskAMissingIdentifier() {
    // Order matters: structural problems are reported as MALFORMED even when the version is also
    // wrong, because the two lead to different operator conclusions.
    OutboxEventEnvelope noOrg =
        new OutboxEventEnvelope(
            EVENT_ID, OutboxEventType.AGENT_RUN_REQUESTED, AGGREGATE_ID, null, 99, Instant.now());

    assertThat(OutboxEnvelopeVerdict.classify(noOrg)).isEqualTo(OutboxEnvelopeVerdict.MALFORMED);
  }

  @Test
  void everyMissingIdentifierIsMalformed() {
    assertThat(OutboxEnvelopeVerdict.classify(null)).isEqualTo(OutboxEnvelopeVerdict.MALFORMED);
    assertThat(
            OutboxEnvelopeVerdict.classify(
                new OutboxEventEnvelope(
                    null,
                    OutboxEventType.AGENT_RUN_RESUME,
                    AGGREGATE_ID,
                    ORG_ID,
                    1,
                    Instant.now())))
        .isEqualTo(OutboxEnvelopeVerdict.MALFORMED);
    assertThat(
            OutboxEnvelopeVerdict.classify(
                new OutboxEventEnvelope(
                    EVENT_ID, OutboxEventType.AGENT_RUN_RESUME, null, ORG_ID, 1, Instant.now())))
        .isEqualTo(OutboxEnvelopeVerdict.MALFORMED);
    assertThat(OutboxEnvelopeVerdict.classify(envelope("   ", 1)))
        .isEqualTo(OutboxEnvelopeVerdict.MALFORMED);
  }

  @Test
  void anUnpersistedRowCannotProduceAPublishableEnvelope() {
    // The guard that matters most in practice: `of()` reads the row's generated id, so a row that
    // was never flushed yields a null eventId — and a consumer with no eventId cannot de-duplicate
    // anything. Classification refuses it rather than publishing an unidentifiable message.
    OutboxEvent unsaved =
        new OutboxEvent(
            OutboxEventType.AGENT_RUN_REQUESTED, "agent_run", AGGREGATE_ID, ORG_ID, "{}");

    OutboxEventEnvelope envelope = OutboxEventEnvelope.of(unsaved);

    assertThat(envelope.eventId()).isNull();
    assertThat(OutboxEnvelopeVerdict.classify(envelope)).isEqualTo(OutboxEnvelopeVerdict.MALFORMED);
  }

  @Test
  void theEnvelopeCarriesIdentifiersOnlyAndNeverThePayload() {
    // docs/API_CONTRACT.md: "Published event payload only contains identifiers; workers reload
    // trusted DB state." Enforced by construction — the record has no payload component — and
    // asserted here so adding one has to break a test that says why it must not.
    assertThat(OutboxEventEnvelope.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .containsExactlyInAnyOrder(
            "eventId", "type", "aggregateId", "organizationId", "schemaVersion", "createdAt");
  }

  private static OutboxEnvelopeVerdict envelopeVerdictAtVersion(int version) {
    return OutboxEnvelopeVerdict.classify(
        envelope(OutboxEventType.KNOWLEDGE_INGEST_REQUESTED, version));
  }

  private static OutboxEventEnvelope envelope(String type, int schemaVersion) {
    return new OutboxEventEnvelope(
        EVENT_ID, type, AGGREGATE_ID, ORG_ID, schemaVersion, Instant.parse("2026-01-01T00:00:00Z"));
  }
}
