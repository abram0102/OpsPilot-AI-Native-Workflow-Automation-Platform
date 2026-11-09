# services/agent

Python 3.12 / LangGraph AI worker. Skeleton implemented in ticket **T03** (`docs/TASKS.md`): env-backed config (`AI_MODE=mock` needs no API key; `AI_MODE=real` fails fast without one) and a health-only FastAPI app.

The provider layer landed in **T15**: typed structured outputs, a deterministic seeded mock, a real env-driven HTTP adapter with timeouts/retry budget/circuit breaking, embedding dimension checks, a stable error taxonomy and version-controlled prompts. No Redis consumer or LangGraph graph yet — those start at T19/T25, and the *live* provider call (plus per-run budget counters and the opt-in eval) is T20, gated on a real API key.

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
  prompts/
    __init__.py          # PromptName + loader; a revision is a file, not an edit
    classification.v1.md # Version-controlled prompts (docs/SECURITY_AND_TESTING.md §5)
    reply_draft.v1.md
  providers/
    protocols.py         # LLMProvider / EmbeddingProvider Protocol (docs/API_CONTRACT.md)
    schemas.py           # Classification / ReplyDraft structured outputs
    errors.py            # AiErrorCode taxonomy; every failure here is one of these
    factory.py           # The only place AI_MODE is read
    mock.py              # Deterministic, schema-valid, no network calls
    seed_cases.py        # Keyword rules seeding the mock (NOT the eval dataset — T30)
    http.py              # Real adapter; injectable httpx client, unverified live (Gate H2)
    resilience.py        # Retry budget + circuit breaker
    tokens.py            # Input budgeting (an estimate, never a measurement)
    redaction.py         # Keeps the API key out of logs and error messages
tests/
```

`worker`, `retrieval`, `graphs`, `tools`, `evals`, `telemetry` (per `AGENTS.md`'s expected layout) are added by the tickets that need them (T16, T19, T25, T30), not speculatively ahead of time.

## Providers

```python
settings = Settings()
llm = build_llm_provider(settings)  # MockLLMProvider when AI_MODE=mock
embeddings = build_embedding_provider(settings)
```

`AI_MODE` is read only inside `providers/factory.py`, so nothing downstream can branch on it or reach a real provider by going around it. Every failure raises `AiProviderError` with a stable `AiErrorCode` (`AI_TIMEOUT`, `AI_INVALID_OUTPUT`, `AI_RATE_LIMITED`, `AI_CIRCUIT_OPEN`, `AI_INPUT_TOO_LARGE`, `AI_DIMENSION_MISMATCH`, `AI_PROVIDER_ERROR`, `AI_NOT_CONFIGURED`).

The real adapter is **not verified against a live provider** — no API key exists in this environment (Gate H2) and a real call would incur paid usage. Its request shaping and every failure path *are* covered, via `httpx.MockTransport`.

## Docker

`Dockerfile` builds a non-root, `uv`-managed image. **Not yet build-verified** — Docker isn't installed in the environment this ticket was implemented in (see `docs/PROGRESS.md` T03, same Gate H0 as T01/T02). Verify with `docker build .` once Docker is available.
