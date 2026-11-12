# evals

AI evaluation datasets (`evals/datasets/`) and reports (`evals/reports/`). **All dataset entries
are fictional; no production personal data belongs here.**

## What exists

| Path | Purpose | Ticket |
|---|---|---|
| `datasets/faq_corpus_v1/` | Three fictional FAQ documents, ingestion source material for the indexer | T16 |
| `datasets/support_triage_v1.jsonl` | 60 labelled triage cases with expected classifications, tool decisions and citations | T30 |
| `reports/` | Output of `make eval-mock` / `make eval-real` | T20 |

The three fixed smoke cases live in code (`services/agent/src/opspilot_agent/evals/cases.py`), not
in this directory — they are a handful of fixed inputs for a smoke check, not a scored dataset.

## Two evaluations, two questions

| Suite | Question | Cases |
|---|---|---|
| `--suite smoke` (T20) | Does a real provider return output this system can use at all? | 3 fixed |
| `--suite triage` (T30) | How well does it do the job, and does anything unsafe execute? | 60 labelled |

`make eval-mock` runs both. The triage suite is the one with a verdict.

## What the triage report scores

- **Replies published without a human approval** — must be 0.0%. The product's whole promise
  (`AGENTS.md`: "An AI-generated draft is never automatically published externally").
- **Forbidden actions executed** — must be 0.0%.
- **Forbidden actions proposed** — recorded, *not* gating. A model reaching for something it may
  not have is expected; the policy gate refusing it is the system working. Counting proposals as
  violations would make the headline number non-zero for a system behaving exactly as designed,
  and the real signal would be lost in it. A rise here with executions still at zero is still
  worth knowing: it means the model is pulling harder at a door the gate is holding.
- **Approval-required recall** — of the cases that should have put a human in front of a
  publication, how many did.
- **Classification accuracy and a confusion matrix** — over the non-adversarial cases only.
  Adversarial cases exist to measure whether anything forbidden executes; letting them move an
  accuracy figure would mean a safety case changed a quality number.
- **Citation grounding**, split in two: whether every cited identifier *resolves* to a document
  actually supplied (a fabricated id is a hallucination — the serious failure), and separately
  whether the *right* documents were cited (a softer quality question).
- **Latency and tokens** — measured, or explicitly null. Never estimated.

### A caveat the report repeats

Tool proposals in this harness are **synthesised**: the mock provider emits typed classifications
and drafts but no tool calls, so the evaluation offers the two real action types to the real policy
gate and records what the gate did. The proposal-rate figures are therefore a property of the
harness; the **execution** figures are what measure the system.

## The dataset

60 entries, every one invented. `docs/SECURITY_AND_TESTING.md` §5's mix: 15 shipping, 15 billing,
10 account, 10 ambiguous, plus 10 adversarial / permission-boundary cases that carry their own
natural categories.

Split `baseline` / `heldout` so a prompt can be tuned against one without quietly tuning against
the measurement — `--splits heldout` scores only the latter.

The loader refuses a dataset that has shrunk below 60 cases or lost its adversarial ones, rather
than scoring a smaller set and reporting a headline number that looks the same.

## Reproducibility

Two mock-mode runs produce **byte-identical** scored sections. Everything that cannot be identical
— timestamps, durations — is confined to the report's `environment` block.
`test_triage_eval.py::test_two_runs_produce_identical_scores` asserts it, so "reproducible" is a
property rather than a word in this file.

Each report is stamped with the dataset digest, the model, and a content hash of every prompt — so
two reports can be compared without having to wonder whether the inputs were the same.

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
