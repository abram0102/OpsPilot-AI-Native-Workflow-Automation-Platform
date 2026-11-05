# services/agent

Python 3.12 / LangGraph AI worker. Skeleton implemented in ticket **T03** (`docs/TASKS.md`): env-backed config (`AI_MODE=mock` needs no API key; `AI_MODE=real` fails fast without one), the `LLMProvider`/`EmbeddingProvider` protocols plus deterministic mock implementations, and a health-only FastAPI app. No Redis consumer, LangGraph graph, or real provider logic yet — those start at T15/T19/T25.

## Running locally

```bash
cd services/agent
uv sync
AI_MODE=mock uv run uvicorn opspilot_agent.main:app --reload --port 8000
```

- `GET /health/live` — process is up; never depends on configuration.
- `GET /health/ready` — process is up AND configuration is valid (fails if `AI_MODE=real` is set without `LLM_API_KEY`).

## Checks

```bash
uv run ruff check .
uv run pytest
```

Both run with no external services and no API key, by design (`AI_MODE=mock` is the default).

## Structure

```
src/opspilot_agent/
  config.py              # Settings (pydantic-settings), AiMode enum
  main.py                # FastAPI app, health endpoints only
  providers/
    protocols.py         # LLMProvider / EmbeddingProvider Protocol (docs/API_CONTRACT.md)
    mock.py               # Deterministic mock implementations, no network calls
tests/
```

`worker`, `retrieval`, `graphs`, `tools`, `evals`, `telemetry` (per `AGENTS.md`'s expected layout) are added by the tickets that need them (T16, T19, T25, T30), not speculatively ahead of time.

## Docker

`Dockerfile` builds a non-root, `uv`-managed image. **Not yet build-verified** — Docker isn't installed in the environment this ticket was implemented in (see `docs/PROGRESS.md` T03, same Gate H0 as T01/T02). Verify with `docker build .` once Docker is available.
