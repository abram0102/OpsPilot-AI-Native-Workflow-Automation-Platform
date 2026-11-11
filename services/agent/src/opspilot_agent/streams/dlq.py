"""The dead-letter stream, and the rule for what is allowed into it.

docs/SECURITY_AND_TESTING.md: a poison message goes to a "DLQ or terminal failure with
operator-visible reason; no endless tight retry loop". Two things follow from that sentence and
both are enforced here.

*Operator-visible*: the record names the event, the type, the verdict or error code, how many
times it was delivered and which worker gave up. That is enough to find the originating outbox row
in Postgres, which still holds the full payload.

*Sanitized*: the reason is a **code and an exception class name, never an exception message**. A
handler's message can quote a ticket body, a provider response or a row's contents — exactly the
data docs/SECURITY_AND_TESTING.md says must not be logged. The detail is therefore constructed
from a fixed vocabulary rather than passed through, and a caller cannot smuggle text into it.

What does *not* come here: a run that genuinely failed, or a document the indexer marked FAILED.
Those are recorded on their own rows with an error code, which is the authoritative place to read
them; duplicating them onto the dead-letter stream would bury the messages that could not be
processed at all under ones that were processed exactly as designed.
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime

from redis.asyncio import Redis

from opspilot_agent.streams.envelope import Envelope

log = logging.getLogger(__name__)


async def dead_letter(
    redis: Redis,
    *,
    stream: str,
    message_id: str,
    envelope: Envelope | None,
    reason: str,
    delivery_count: int,
    worker: str,
) -> str:
    """Appends one dead-letter record and returns its id.

    `reason` is a code chosen by the caller from its own fixed set — never user, LLM or database
    text. Anything else is a disclosure waiting to happen.
    """
    record = {
        "originalMessageId": message_id,
        "reason": reason,
        "deliveryCount": str(delivery_count),
        "worker": worker,
        "deadLetteredAt": datetime.now(UTC).isoformat(),
    }
    if envelope is not None:
        record["eventId"] = str(envelope.event_id)
        record["type"] = envelope.type
        record["aggregateId"] = str(envelope.aggregate_id)
        record["organizationId"] = str(envelope.organization_id)
        record["schemaVersion"] = str(envelope.schema_version)

    entry_id = await redis.xadd(stream, record)
    log.error(
        "Dead-lettered message %s (reason=%s, deliveries=%d) as %s.",
        message_id,
        reason,
        delivery_count,
        entry_id,
    )
    return entry_id
