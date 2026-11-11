"""T25: the wire format, classified without any infrastructure.

These are the rules this worker and T24's Java publisher have to agree on. They are written out
twice, once per language, so the tests on both sides are what keep them in step — the constants
asserted here are the ones `OutboxEnvelopeTest` asserts on the other side.
"""

from __future__ import annotations

import json
from uuid import uuid4

from opspilot_agent.streams.envelope import (
    AGENT_RUN_REQUESTED,
    AGENT_RUN_RESUME,
    HANDLED_TYPES,
    KNOWLEDGE_INGEST_REQUESTED,
    PAYLOAD_FIELD,
    SCHEMA_VERSION,
    EnvelopeVerdict,
    parse_envelope,
)


def _fields(**overrides) -> dict[str, str]:
    document = {
        "eventId": str(uuid4()),
        "type": AGENT_RUN_REQUESTED,
        "aggregateId": str(uuid4()),
        "organizationId": str(uuid4()),
        "schemaVersion": SCHEMA_VERSION,
        "createdAt": "2026-11-11T12:00:00Z",
    }
    document.update(overrides)
    for key in [k for k, v in document.items() if v is None]:
        del document[key]
    return {PAYLOAD_FIELD: json.dumps(document)}


def test_the_contract_constants_match_the_publisher():
    # Pinned so that changing either side alone fails a test rather than silently halving
    # delivery. docs/API_CONTRACT.md fixes all three.
    assert SCHEMA_VERSION == 1
    assert PAYLOAD_FIELD == "envelope"
    assert HANDLED_TYPES == {
        "knowledge.ingest.requested",
        "agent.run.requested",
        "agent.run.resume",
    }


def test_a_well_formed_envelope_is_accepted_and_fully_parsed():
    event_id, aggregate_id, organization_id = uuid4(), uuid4(), uuid4()
    envelope, verdict = parse_envelope(
        _fields(
            eventId=str(event_id),
            aggregateId=str(aggregate_id),
            organizationId=str(organization_id),
            type=KNOWLEDGE_INGEST_REQUESTED,
        )
    )

    assert verdict is EnvelopeVerdict.ACCEPTED
    assert verdict.accepted
    assert envelope is not None
    assert envelope.event_id == event_id
    assert envelope.aggregate_id == aggregate_id
    assert envelope.organization_id == organization_id
    assert envelope.type == KNOWLEDGE_INGEST_REQUESTED
    assert envelope.created_at is not None


def test_every_handled_type_is_accepted():
    for event_type in (KNOWLEDGE_INGEST_REQUESTED, AGENT_RUN_REQUESTED, AGENT_RUN_RESUME):
        _, verdict = parse_envelope(_fields(type=event_type))
        assert verdict is EnvelopeVerdict.ACCEPTED, event_type


def test_the_pre_t24_knowledge_type_is_not_routable():
    # T24 renamed it; a message still carrying the old name belongs to no handler here.
    _, verdict = parse_envelope(_fields(type="knowledge.document.queued"))
    assert verdict is EnvelopeVerdict.UNKNOWN_TYPE


def test_an_unknown_type_is_rejected_but_still_identified():
    envelope, verdict = parse_envelope(_fields(type="ticket.deleted"))

    assert verdict is EnvelopeVerdict.UNKNOWN_TYPE
    # Rejected messages still come back parsed, so the dead-letter record can name the event an
    # operator has to go and look at.
    assert envelope is not None
    assert envelope.type == "ticket.deleted"


def test_a_newer_schema_version_is_refused_rather_than_guessed_at():
    _, verdict = parse_envelope(_fields(schemaVersion=SCHEMA_VERSION + 1))
    assert verdict is EnvelopeVerdict.UNKNOWN_SCHEMA_VERSION


def test_a_missing_schema_version_reads_as_zero_and_is_refused():
    # The reason the check is `!= SCHEMA_VERSION` and not `<`: an absent field must not pass.
    _, verdict = parse_envelope(_fields(schemaVersion=None))
    assert verdict is EnvelopeVerdict.UNKNOWN_SCHEMA_VERSION


def test_a_non_numeric_schema_version_is_refused_not_crashed_on():
    _, verdict = parse_envelope(_fields(schemaVersion="not-a-number"))
    assert verdict is EnvelopeVerdict.UNKNOWN_SCHEMA_VERSION


def test_version_is_checked_before_type_so_a_future_type_reads_as_a_version_problem():
    # A message from a newer deployment may legitimately carry a type this build has never heard
    # of. Reporting that as UNKNOWN_TYPE would send an operator looking for a routing bug.
    _, verdict = parse_envelope(
        _fields(schemaVersion=SCHEMA_VERSION + 1, type="something.from.the.future")
    )
    assert verdict is EnvelopeVerdict.UNKNOWN_SCHEMA_VERSION


def test_missing_identifiers_are_malformed():
    for missing in ("eventId", "aggregateId", "organizationId", "type"):
        envelope, verdict = parse_envelope(_fields(**{missing: None}))
        assert verdict is EnvelopeVerdict.MALFORMED, missing
        assert envelope is None


def test_a_blank_type_is_malformed_rather_than_unknown():
    _, verdict = parse_envelope(_fields(type="   "))
    assert verdict is EnvelopeVerdict.MALFORMED


def test_an_identifier_that_is_not_a_uuid_is_malformed():
    _, verdict = parse_envelope(_fields(aggregateId="not-a-uuid"))
    assert verdict is EnvelopeVerdict.MALFORMED


def test_a_missing_or_unparseable_payload_field_is_malformed():
    assert parse_envelope({})[1] is EnvelopeVerdict.MALFORMED
    assert parse_envelope({PAYLOAD_FIELD: ""})[1] is EnvelopeVerdict.MALFORMED
    assert parse_envelope({PAYLOAD_FIELD: "{not json"})[1] is EnvelopeVerdict.MALFORMED
    # Valid JSON, wrong shape: a bare list or string is not an envelope.
    assert parse_envelope({PAYLOAD_FIELD: "[]"})[1] is EnvelopeVerdict.MALFORMED
    assert parse_envelope({PAYLOAD_FIELD: '"envelope"'})[1] is EnvelopeVerdict.MALFORMED


def test_an_unreadable_created_at_does_not_reject_an_otherwise_valid_event():
    # createdAt is informational. Refusing a real event over a timestamp format would lose work
    # for no safety benefit.
    envelope, verdict = parse_envelope(_fields(createdAt="yesterday"))

    assert verdict is EnvelopeVerdict.ACCEPTED
    assert envelope is not None
    assert envelope.created_at is None
