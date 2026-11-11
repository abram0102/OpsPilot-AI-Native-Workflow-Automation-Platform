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
  knowledge/
    extraction.py        # TXT/MD/PDF with sandbox + decompression guards
    chunking.py          # ~600 tokens, ~100 overlap (approximate — no tokenizer)
    indexer.py           # idempotent indexing, status transitions, deletion races
    retrieval.py         # exact cosine, org + READY filtered in SQL
    storage.py           # reads the Java local adapter's root, containment-checked
    handler.py           # ingest handler + CLI (Redis consumer is T25)
  db.py                  # psycopg connection; Flyway still owns the schema
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

`worker`, `tools`, `telemetry` (per `AGENTS.md`'s expected layout) are added by the tickets that need them (T25, T30), not speculatively ahead of time.

## The triage graph (T19)

```
load_ticket → classify → retrieve_knowledge → draft_reply → propose_actions → policy_gate → finalize
```

A straight line with no cycles — "no open-ended recursive loops" is guaranteed by a graph that cannot loop, not by a step counter alone (though steps, tokens and wall-clock time are all bounded too, each failing the run with its own code).

```bash
# Execute one run (stand-in for the T25 Redis consumer)
uv run python -m opspilot_agent.graphs.handler \
  --organization-id <uuid> --run-id <uuid> --ticket-id <uuid>
```

Since **T23** the graph pauses and resumes for real:

```
policy_gate ─(needs approval?)→ persist_approval → await_approval → execute_approved_action → finalize
            └──────── no ─────────────────────────────────────────────────────────────────→ finalize
```

`await_approval` contains nothing but LangGraph's `interrupt()`, because **resuming re-runs the interrupted node from its start** — code before an interrupt executes twice, code after it once. The action that publishes therefore lives downstream, where it runs exactly once. On resume the claimed decision is ignored and the approval is re-read from the database; only a stored `APPROVED` publishes anything. See `docs/DECISIONS.md` ADR-047, including the crash windows the two non-atomic writes leave.

`graphs/policy.py` is deterministic code with no model in the loop, and is the component worth reading first: it is where "AI cannot choose which permissions it has" is actually enforced. State is checkpointed to Postgres under `thread_id = agent_runs.id`; those tables belong to the checkpointer rather than Flyway (`docs/DATA_MODEL.md`, ADR-043).

## Knowledge indexing and retrieval (T16)

The worker indexes documents and searches them directly against PostgreSQL + pgvector. That is narrower than it sounds: its *business actions* on tickets still go through the Java internal API (T21), and nothing here touches tickets, approvals or runs. Flyway in `services/api` owns the schema; this service creates no tables. See `docs/DECISIONS.md` ADR-040.

```bash
# Index one document (stand-in for the T25 Redis consumer)
uv run python -m opspilot_agent.knowledge.handler \
  --organization-id <uuid> --document-id <uuid>
```

Retrieval always filters on `organization_id` **and** `status = 'READY'` in the SQL itself, never on the results afterwards — the failure mode would be silent cross-tenant disclosure.

### Database-backed tests

Tests marked `db` need PostgreSQL with pgvector and are **skipped unless `POSTGRES_TEST_DSN` is set**, because this project's development environment has no Docker (Gate H0):

```bash
uv run pytest -m 'not db'   # what runs on a machine without a database
uv run pytest -m db         # needs POSTGRES_TEST_DSN
```

CI supplies a `pgvector/pgvector:pg16` service container and runs the `db` selection as its own step, so a skip there fails the build rather than passing quietly.

## Providers

```python
settings = Settings()
llm = build_llm_provider(settings)  # MockLLMProvider when AI_MODE=mock
embeddings = build_embedding_provider(settings)
```

`AI_MODE` is read only inside `providers/factory.py`, so nothing downstream can branch on it or reach a real provider by going around it. Every failure raises `AiProviderError` with a stable `AiErrorCode` (`AI_TIMEOUT`, `AI_INVALID_OUTPUT`, `AI_RATE_LIMITED`, `AI_CIRCUIT_OPEN`, `AI_INPUT_TOO_LARGE`, `AI_DIMENSION_MISMATCH`, `AI_PROVIDER_ERROR`, `AI_NOT_CONFIGURED`).

The real adapter is **not verified against a live provider** — no API key exists in this environment (Gate H2) and a real call would incur paid usage. Its request shaping and every failure path *are* covered, via `httpx.MockTransport`.

### Evaluations (T20)

```bash
make eval-mock    # offline, no API key, no cost. The default.
make eval-real    # BILLABLE. Refuses unless Gate H2 is genuinely set up.
```

Paid usage needs **two** switches: `AI_MODE=real` *and* `ALLOW_REAL_LLM_CALLS=true`. The second exists only to make spending deliberate — real mode alone will not call a provider, because a copied `.env` should not be able to start a bill. `EVAL_MAX_CASES` caps a real run.

Reports record the provider's own token counts and model id. Cost is **null** unless you set `LLM_INPUT_COST_PER_MTOK`/`LLM_OUTPUT_COST_PER_MTOK`; no price table ships, since prices change and a stale one reads as fact. See `evals/README.md` for exactly what a real run transmits.

## Docker

`Dockerfile` builds a non-root, `uv`-managed image. **Not yet build-verified** — Docker isn't installed in the environment this ticket was implemented in (see `docs/PROGRESS.md` T03, same Gate H0 as T01/T02). Verify with `docker build .` once Docker is available.
