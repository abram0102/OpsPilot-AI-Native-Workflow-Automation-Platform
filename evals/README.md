# evals

AI evaluation datasets (`evals/datasets/`) and reports (`evals/reports/`). **All dataset entries
are fictional; no production personal data belongs here.**

## What exists

| Path | Purpose | Ticket |
|---|---|---|
| `datasets/faq_corpus_v1/` | Three fictional FAQ documents, ingestion source material for the indexer | T16 |
| `datasets/support_triage_v1.jsonl` | 60+ labelled triage cases with expected classifications and tool decisions, plus the scoring harness | **T30, not yet built** |
| `reports/` | Output of `make eval-mock` / `make eval-real` | T20 |

The three fixed smoke cases that `make eval-*` runs live in code
(`services/agent/src/opspilot_agent/evals/cases.py`), not in this directory — they are a handful of
fixed inputs for a smoke check, not a scored dataset.

## Running

```bash
make eval-mock    # offline, no API key, no cost. The default.
make eval-real    # BILLABLE. Refuses unless Gate H2 is genuinely set up.
```

`eval-real` requires **two** independent switches: `AI_MODE=real` *and* `ALLOW_REAL_LLM_CALLS=true`.
The second exists only to make paid usage a deliberate act — a copied `.env` or a stray environment
variable must not be able to start spending money. Without them the command prints the full setup
checklist and exits non-zero; it never silently falls back to mock.

`EVAL_MAX_CASES` (default 3) caps how many cases a real run may execute, so a misconfiguration
cannot become an unbounded bill.

## Provider data handling — synthetic demo content only

When `make eval-real` runs, the text below leaves this machine and is sent to the configured
third-party LLM provider:

- the three fixed smoke tickets from `cases.py`,
- one fictional FAQ excerpt used to check citation behaviour,
- the two version-controlled prompts in `services/agent/src/opspilot_agent/prompts/`.

Every one of those is **invented for this project**. No real customer text, no real ticket, no
personal data and no credential is included, and the evaluation deliberately does not read from the
database — so a production or staging tenant's content cannot be sent to a provider by running it.

Before enabling Gate H2, confirm the provider's data-retention and training policies are acceptable
*for synthetic demo text*, which is the only thing this path ever transmits
(`docs/MANUAL_SETUP.md` Gate H2 step 2). If this project later evaluates against real tenant data,
that is a different decision with a different review, and this section would no longer describe it.

## Cost reporting

Reports record the **provider's own** token counts, read from its `usage` block — never this
project's internal `estimate_tokens` heuristic, which exists only as a request-size guard rail.

A dollar figure appears only when `LLM_INPUT_COST_PER_MTOK` and `LLM_OUTPUT_COST_PER_MTOK` are set
to your actual contracted rates. Unset, the report records a **null** cost. This project ships no
price table on purpose: prices change by model, region and account, and a stale table in a report
reads as fact. A null cost is a true statement about what the run knows.
