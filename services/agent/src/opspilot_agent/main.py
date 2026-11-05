"""FastAPI app exposing health only (ticket T03). No agent/worker logic yet —
that starts with the Redis consumer in T25 and the LangGraph triage graph in
T19.
"""

from __future__ import annotations

from fastapi import FastAPI

from opspilot_agent.config import get_settings

app = FastAPI(title="OpsPilot AI Worker", version="0.1.0")


@app.get("/health/live")
async def health_live() -> dict[str, str]:
    """Process is up. Never depends on configuration or external services."""
    return {"status": "ok"}


@app.get("/health/ready")
async def health_ready() -> dict[str, str]:
    """Process is up AND configuration is valid.

    There is no worker loop, DB lease, or Redis consumer yet (T16/T19/T25),
    so "ready" currently only confirms settings load without error — e.g. it
    will fail if AI_MODE=real is set without an LLM_API_KEY.
    """
    get_settings()
    return {"status": "ok"}
