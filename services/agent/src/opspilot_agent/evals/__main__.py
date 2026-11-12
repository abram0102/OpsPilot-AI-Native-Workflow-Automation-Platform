"""CLI for the evaluations. Backs `make eval-mock` and `make eval-real`.

Two evaluations, because they answer different questions. The **smoke** run (T20) puts three fixed
tickets through the provider and checks the output is usable at all — it is what you want when
pointing at a real provider for the first time, and it is capped so a misconfiguration cannot
become a bill. The **triage** run (T30) scores sixty labelled cases and is the one with a verdict:
classification accuracy, citation grounding, and the forbidden-action rate that must be zero.

`--suite` picks. The default is both, because `make eval-mock` costs nothing and a developer who
runs only the smoke set would not notice a scored regression.

Mock is the default and needs nothing. Real refuses unless Gate H2 is genuinely in place, printing
the full checklist rather than a single error — see `preflight.refusal_message`.
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

from opspilot_agent.config import AiMode, get_settings
from opspilot_agent.evals.preflight import check_real_eval_allowed, refusal_message
from opspilot_agent.evals.smoke import run_smoke_eval, write_report
from opspilot_agent.evals.triage import render_markdown, run_triage_eval, write_reports

# parents[5] is the repository root: evals/__main__.py -> evals -> opspilot_agent -> src ->
# agent -> services -> <root>. Counted rather than guessed, after an off-by-one wrote a report
# into services/evals/ on the first run.
DEFAULT_REPORT_DIR = Path(__file__).resolve().parents[5] / "evals" / "reports"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m opspilot_agent.evals",
        description="Run the smoke evaluation (mock by default; real requires Gate H2).",
    )
    parser.add_argument(
        "--real",
        action="store_true",
        help="Call the configured provider. Billable, and gated on Gate H2.",
    )
    parser.add_argument("--max-cases", type=int, default=None)
    parser.add_argument("--report-dir", type=Path, default=DEFAULT_REPORT_DIR)
    parser.add_argument(
        "--suite",
        choices=("smoke", "triage", "all"),
        default="all",
        help="smoke = T20's three fixed cases; triage = T30's scored dataset; all = both.",
    )
    parser.add_argument(
        "--splits",
        default="baseline,heldout",
        help="Which dataset splits the triage suite scores. Held-out exists so a prompt can be "
        "tuned against baseline without quietly tuning against the measurement.",
    )
    args = parser.parse_args(argv)

    settings = get_settings()

    if args.real:
        decision = check_real_eval_allowed(settings)
        if decision.refused:
            # Not an unexpected failure: this is the normal state of this repository, and the
            # message is part of what T20 is required to deliver.
            print(refusal_message(decision, settings), file=sys.stderr)
            return 2
    elif settings.ai_mode is AiMode.REAL:
        # Guard against the opposite mistake: a real-mode environment running what the operator
        # believes is the offline evaluation.
        print(
            "AI_MODE=real is set but --real was not passed. Refusing to guess which you meant; "
            "re-run with --real to make billable calls, or AI_MODE=mock for the offline run.",
            file=sys.stderr,
        )
        return 2

    settings_for_run = (
        settings if args.real else settings.model_copy(update={"ai_mode": AiMode.MOCK})
    )

    exit_code = 0

    if args.suite in ("smoke", "all"):
        smoke = asyncio.run(run_smoke_eval(settings_for_run, max_cases=args.max_cases))
        smoke_path = write_report(smoke, args.report_dir)
        print(f"Smoke report written to {smoke_path}", file=sys.stderr)
        if not smoke.all_ok:
            exit_code = 1

    if args.suite in ("triage", "all"):
        splits = tuple(s.strip() for s in args.splits.split(",") if s.strip())
        triage = asyncio.run(
            run_triage_eval(settings_for_run, splits=splits, max_cases=args.max_cases)
        )
        json_path, md_path = write_reports(triage, args.report_dir)
        # The Markdown goes to stdout: it is the thing a person reads, and burying it in a file
        # while printing JSON would mean nobody ever sees the verdict without opening something.
        print(render_markdown(triage))
        print(f"\nTriage reports written to {json_path} and {md_path}", file=sys.stderr)
        if not triage.passed:
            exit_code = 1

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
