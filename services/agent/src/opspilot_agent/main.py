"""FastAPI app exposing health and queue depth.

Still not a work interface: the consumer loop runs as its own process
(`python -m opspilot_agent.streams`), so nothing here executes a run. What T25
adds is the backlog view docs/API_CONTRACT.md asks for ("alert on backlog"),
read straight from Redis rather than from one worker's counters — a single
process's totals say nothing about the queue.
"""

from __future__ import annotations

from fastapi import FastAPI
from redis.exceptions import RedisError

from opspilot_agent.config import get_settings
from opspilot_agent.streams.client import build_redis
from opspilot_agent.streams.consumer import queue_health

app = FastAPI(title="OpsPilot AI Worker", version="0.1.0")


@app.get("/health/live")
async def health_live() -> dict[str, str]:
    """Process is up. Never depends on configuration or external services."""
    return {"status": "ok"}


@app.get("/health/ready")
async def health_ready() -> dict[str, str]:
    """Process is up AND configuration is valid.

    Deliberately does not reach Redis or Postgres. This process serves health
    only; readiness here must not go red because a queue is restarting, for the
    same reason the API keeps Redis out of its readiness group (ADR-048). The
    queue's own state is reported separately, below.
    """
    get_settings()
    return {"status": "ok"}


@app.get("/health/queue")
async def health_queue() -> dict[str, object]:
    """Stream length, pending entries, group lag and dead-letter depth.

    Returns `status: "unavailable"` with the Redis error class — never its message, which can
    carry connection strings — rather than a 500, so a monitoring check can tell "the queue is
    backed up" apart from "the queue cannot be reached". A backlog is a number; an outage is not.
    """
    settings = get_settings()
    redis = build_redis(settings)
    try:
        return {"status": "ok", **await queue_health(redis, settings)}
    except RedisError as error:
        return {"status": "unavailable", "error": type(error).__name__}
    finally:
        await redis.aclose()
