"""CLI for the smoke evaluation. Backs `make eval-mock` and `make eval-real`.

Mock is the default and needs nothing. Real refuses unless Gate H2 is genuinely
in place, printing the full checklist rather than a single error — see
`preflight.refusal_message`.
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

from opspilot_agent.config import AiMode, get_settings
from opspilot_agent.evals.preflight import check_real_eval_allowed, refusal_message
from opspilot_agent.evals.smoke import run_smoke_eval, write_report

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

    report = asyncio.run(run_smoke_eval(settings_for_run, max_cases=args.max_cases))
    path = write_report(report, args.report_dir)

    print(report.to_json())
    print(f"\nReport written to {path}", file=sys.stderr)
    return 0 if report.all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
