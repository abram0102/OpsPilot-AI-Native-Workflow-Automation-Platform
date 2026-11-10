"""The gate in front of paid provider calls.

Separated from the runner so the refusal is testable on its own, and so there is
exactly one place that decides whether money may be spent.

AGENTS.md rule 15: paid API usage requires "the human's explicit authorization".
That is read here as *two* independent locks, not one — `AI_MODE=real` is a
routine configuration value that a stray environment or a copied `.env` could
set, so on its own it must not be enough to start spending. The second lock,
`ALLOW_REAL_LLM_CALLS`, exists only for this purpose and has no other effect
anywhere in the system.

A refusal is not an error state: running without Gate H2 is the normal, expected
condition of this repository. T20's own acceptance requires that the real eval
"refuses with meaningful setup instructions", so the message below is part of the
deliverable rather than an afterthought.
"""

from __future__ import annotations

from dataclasses import dataclass

from opspilot_agent.config import AiMode, Settings

GATE_DOC = "docs/MANUAL_SETUP.md (Gate H2)"


@dataclass(frozen=True)
class PreflightResult:
    allowed: bool
    reasons: tuple[str, ...] = ()

    @property
    def refused(self) -> bool:
        return not self.allowed


def check_real_eval_allowed(settings: Settings) -> PreflightResult:
    """Every reason the real evaluation may not run, reported together.

    All reasons at once rather than the first one: an operator setting this up deserves the whole
    checklist, not three consecutive failures each revealing one more requirement.
    """
    reasons: list[str] = []

    if settings.ai_mode is not AiMode.REAL:
        reasons.append(
            f"AI_MODE is '{settings.ai_mode.value}', not 'real'. The offline evaluation is the "
            "default on purpose; set AI_MODE=real only when you intend to call a paid provider."
        )

    if not settings.allow_real_llm_calls:
        reasons.append(
            "ALLOW_REAL_LLM_CALLS is not set. This flag exists solely to make paid usage a "
            "deliberate act — AI_MODE=real alone will not spend money. Set "
            "ALLOW_REAL_LLM_CALLS=true to confirm you accept provider charges."
        )

    if settings.llm_api_key is None or not settings.llm_api_key.get_secret_value():
        reasons.append(
            "LLM_API_KEY is not set. Create a key with an explicit spend cap first — see "
            f"{GATE_DOC} step 1."
        )

    if not settings.llm_model_id:
        reasons.append(
            "LLM_MODEL_ID is not set. Choose a currently available chat model id; this project "
            "deliberately ships no default, because a hardcoded model id goes stale silently."
        )

    if not settings.embedding_model_id:
        reasons.append(
            "EMBEDDING_MODEL_ID is not set. It must emit "
            f"{settings.embedding_dimensions}-dimensional vectors to match the database column "
            f"(see {GATE_DOC} step 1)."
        )

    if settings.eval_max_cases < 1:
        reasons.append("EVAL_MAX_CASES must be at least 1.")

    return PreflightResult(allowed=not reasons, reasons=tuple(reasons))


def refusal_message(result: PreflightResult, settings: Settings) -> str:
    """The text an operator sees. Tells them what is missing *and* what it will cost."""
    lines = [
        "Refusing to run the real-provider evaluation.",
        "",
        "This command makes billable calls to an external LLM provider, so it is gated behind "
        f"Gate H2 ({GATE_DOC}). What is missing:",
        "",
    ]
    lines += [f"  {index}. {reason}" for index, reason in enumerate(result.reasons, start=1)]
    lines += [
        "",
        "When every item above is satisfied the run will execute at most "
        f"{settings.eval_max_cases} case(s) — the cap exists so a misconfiguration cannot turn "
        "into an unbounded bill.",
        "",
        "Cost reporting: set LLM_INPUT_COST_PER_MTOK and LLM_OUTPUT_COST_PER_MTOK to have the "
        "report include a dollar figure. Left unset, the report records token counts and a null "
        "cost rather than estimating one from a price it cannot verify.",
        "",
        "The offline evaluation needs none of this and runs by default: make eval-mock",
    ]
    return "\n".join(lines)
