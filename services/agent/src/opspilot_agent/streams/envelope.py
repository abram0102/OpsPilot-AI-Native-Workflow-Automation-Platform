"""The wire format, and the rules for deciding whether a message can be acted on.

Deliberately a near-mirror of the Java side's `OutboxEventEnvelope` /
`OutboxEnvelopeVerdict` (T24). The two are separate codebases and cannot share a type, so the
agreement between them is the thing most likely to rot: both are pinned to
`docs/API_CONTRACT.md` and both have tests that would fail if one drifted. Writing the rules
twice is the cost of two languages — silently trusting whatever arrives would be worse.

Envelopes carry **identifiers only** ("workers reload trusted DB state"). Nothing here parses a
payload, because nothing puts one on the stream; `dispatch.py` is where the real state is read
back out of Postgres.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum
from typing import Any
from uuid import UUID

SCHEMA_VERSION = 1
"""The envelope version this worker understands. Must match the publisher's constant."""

PAYLOAD_FIELD = "envelope"
"""The single stream field T24's publisher writes the JSON document into."""

KNOWLEDGE_INGEST_REQUESTED = "knowledge.ingest.requested"
AGENT_RUN_REQUESTED = "agent.run.requested"
AGENT_RUN_RESUME = "agent.run.resume"

HANDLED_TYPES = frozenset({KNOWLEDGE_INGEST_REQUESTED, AGENT_RUN_REQUESTED, AGENT_RUN_RESUME})
"""Exactly the three types docs/TASKS.md T25 says this worker handles."""


class EnvelopeVerdict(StrEnum):
    """Why a message is or is not actionable.

    Three rejections rather than one, because they lead somewhere different: a malformed message
    is a bug, an unknown version is a deployment-generation mismatch, and an unknown type is a
    message meant for a consumer that is not this one. All three are dead-lettered, but an
    operator reading the dead-letter stream needs to be able to tell them apart.
    """

    ACCEPTED = "ACCEPTED"
    MALFORMED = "MALFORMED"
    UNKNOWN_SCHEMA_VERSION = "UNKNOWN_SCHEMA_VERSION"
    UNKNOWN_TYPE = "UNKNOWN_TYPE"

    @property
    def accepted(self) -> bool:
        return self is EnvelopeVerdict.ACCEPTED


@dataclass(frozen=True)
class Envelope:
    event_id: UUID
    type: str
    aggregate_id: UUID
    organization_id: UUID
    schema_version: int
    created_at: datetime | None


def parse_envelope(fields: dict[str, str]) -> tuple[Envelope | None, EnvelopeVerdict]:
    """Decodes one stream entry's fields into an envelope and a verdict.

    Returns the envelope even when the verdict rejects it where that is possible, so the
    dead-letter record can name the `eventId` and type involved. A message too broken to yield
    either comes back as `(None, MALFORMED)`.
    """
    raw = fields.get(PAYLOAD_FIELD)
    if not raw:
        return None, EnvelopeVerdict.MALFORMED

    try:
        document = json.loads(raw)
    except (TypeError, ValueError):
        return None, EnvelopeVerdict.MALFORMED
    if not isinstance(document, dict):
        return None, EnvelopeVerdict.MALFORMED

    try:
        event_id = UUID(str(document["eventId"]))
        aggregate_id = UUID(str(document["aggregateId"]))
        organization_id = UUID(str(document["organizationId"]))
        event_type = document["type"]
    except (KeyError, TypeError, ValueError):
        return None, EnvelopeVerdict.MALFORMED

    if not isinstance(event_type, str) or not event_type.strip():
        return None, EnvelopeVerdict.MALFORMED

    envelope = Envelope(
        event_id=event_id,
        type=event_type,
        aggregate_id=aggregate_id,
        organization_id=organization_id,
        # A missing version reads as 0, which must not pass as "fine" — hence the equality check
        # below rather than a minimum.
        schema_version=_int_or_zero(document.get("schemaVersion")),
        created_at=_timestamp_or_none(document.get("createdAt")),
    )

    if envelope.schema_version != SCHEMA_VERSION:
        return envelope, EnvelopeVerdict.UNKNOWN_SCHEMA_VERSION
    if envelope.type not in HANDLED_TYPES:
        return envelope, EnvelopeVerdict.UNKNOWN_TYPE
    return envelope, EnvelopeVerdict.ACCEPTED


def _int_or_zero(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def _timestamp_or_none(value: Any) -> datetime | None:
    """`createdAt` is informational; an unreadable one must not reject an otherwise valid event."""
    if not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
