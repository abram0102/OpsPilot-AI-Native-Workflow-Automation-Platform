"""The Redis connection this worker uses.

`decode_responses=True` throughout: every value on these streams is a JSON document written as
text by the Java publisher, and handling `bytes` everywhere downstream would only invite a
`b'...'` to end up in a log line or a database column.
"""

from __future__ import annotations

from redis.asyncio import Redis

from opspilot_agent.config import Settings


def build_redis(settings: Settings) -> Redis:
    return Redis(
        host=settings.redis_host,
        port=settings.redis_port,
        decode_responses=True,
        # A worker blocked forever on an unreachable Redis looks identical to an idle one. These
        # make an outage visible as an error the loop can log and back off from.
        socket_connect_timeout=5,
        # Must exceed the XREADGROUP block time, or every idle read would look like a timeout.
        socket_timeout=(settings.redis_block_ms / 1000) + 5,
        health_check_interval=30,
    )
