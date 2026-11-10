"""The smoke evaluation: a few fixed tickets through classification and drafting.

Runs against either provider. In mock mode it needs nothing and is the default;
in real mode it is gated by `preflight` and capped by `EVAL_MAX_CASES`.

Deliberately narrower than a full triage run: it exercises the provider layer
(typed classification, a drafted reply, citations over supplied excerpts) without
a database, a run row or the graph. T19 already covers the graph end to end
against Postgres, and the question *this* answers is a different one — does a
real provider return output this system can actually use?

The report records what happened, including what it does not know. A null cost
means no rates were configured; unknown token counts mean the provider reported
none. Neither is filled in with a guess (T20: "not made up").
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path

from opspilot_agent.config import AiMode, Settings
from opspilot_agent.evals.cases import SMOKE_CASES, EvalCase
from opspilot_agent.prompts import PromptName, load_prompt
from opspilot_agent.providers.errors import AiProviderError
from opspilot_agent.providers.factory import build_embedding_provider, build_llm_provider
from opspilot_agent.providers.schemas import Classification, ReplyDraft
from opspilot_agent.providers.usage import UsageLedger

# A fictional excerpt the draft is asked to cite, so "valid citations" is checkable without
# standing up retrieval. Its identifier is what a real citation must match.
REFERENCE_EXCERPT_ID = "faq-shipping#0"
REFERENCE_EXCERPT = (
    "Standard delivery takes three to five working days. A parcel is treated as lost once the "
    "courier has not scanned it for ten working days, at which point a replacement is dispatched "
    "at no cost. Duplicate charges are refunded in full once confirmed. Password reset links "
    "stay valid for one hour."
)


@dataclass
class CaseResult:
    case_id: str
    expected_category: str
    observed_category: str | None = None
    urgency: str | None = None
    confidence: float | None = None
    draft_chars: int | None = None
    citations: list[str] = field(default_factory=list)
    citations_valid: bool | None = None
    error_code: str | None = None

    @property
    def ok(self) -> bool:
        return self.error_code is None and self.observed_category is not None


@dataclass
class EvalReport:
    mode: str
    started_at: str
    finished_at: str
    models_used: list[str]
    cases: list[CaseResult]
    total_input_tokens: int | None
    total_output_tokens: int | None
    total_cost_usd: str | None
    cost_note: str

    @property
    def all_ok(self) -> bool:
        return bool(self.cases) and all(case.ok for case in self.cases)

    def to_json(self) -> str:
        payload = asdict(self)
        payload["cases"] = [asdict(case) for case in self.cases]
        return json.dumps(payload, indent=2, ensure_ascii=False)


async def run_smoke_eval(settings: Settings, *, max_cases: int | None = None) -> EvalReport:
    """Runs the smoke cases. Caller is responsible for having passed preflight in real mode."""
    limit = max_cases if max_cases is not None else settings.eval_max_cases
    cases = SMOKE_CASES[: max(0, limit)]

    ledger = UsageLedger()
    llm = build_llm_provider(settings, usage_ledger=ledger)
    # Built but barely used: the embedding provider is constructed so a real run proves the
    # configured embedding model is reachable and the right width before T16's indexer needs it.
    embeddings = build_embedding_provider(settings, usage_ledger=ledger)

    started = datetime.now(UTC)
    results: list[CaseResult] = []

    for case in cases:
        results.append(await _run_case(settings, llm, case))

    if settings.ai_mode is AiMode.REAL:
        await _probe_embeddings(embeddings, results)

    finished = datetime.now(UTC)
    cost = ledger.total_cost_usd(
        settings.llm_input_cost_per_mtok, settings.llm_output_cost_per_mtok
    )

    return EvalReport(
        mode=settings.ai_mode.value,
        started_at=started.isoformat(),
        finished_at=finished.isoformat(),
        models_used=ledger.models_used() or [_configured_model(settings)],
        cases=results,
        total_input_tokens=ledger.total_input_tokens if ledger.fully_reported else None,
        total_output_tokens=ledger.total_output_tokens if ledger.fully_reported else None,
        total_cost_usd=str(cost) if cost is not None else None,
        cost_note=_cost_note(settings, ledger, cost),
    )


async def _run_case(settings: Settings, llm, case: EvalCase) -> CaseResult:
    result = CaseResult(case_id=case.case_id, expected_category=case.expected_category.value)

    try:
        classification: Classification = await llm.structured_response(
            messages=[
                {"role": "system", "content": load_prompt(PromptName.CLASSIFICATION_V1)},
                {
                    "role": "user",
                    "content": (
                        "Classify the following support ticket.\n\n"
                        "--- BEGIN UNTRUSTED TICKET ---\n"
                        f"Subject: {case.subject}\n\n{case.description}\n"
                        "--- END UNTRUSTED TICKET ---"
                    ),
                },
            ],
            schema=Classification,
            max_output_tokens=settings.agent_max_output_tokens,
            timeout_s=settings.llm_timeout_seconds,
        )
        result.observed_category = classification.category.value
        result.urgency = classification.urgency.value
        result.confidence = classification.confidence

        draft: ReplyDraft = await llm.structured_response(
            messages=[
                {"role": "system", "content": load_prompt(PromptName.REPLY_DRAFT_V1)},
                {
                    "role": "user",
                    "content": (
                        "Draft a reply using only the excerpt provided.\n\n"
                        "--- BEGIN UNTRUSTED TICKET ---\n"
                        f"Subject: {case.subject}\n\n{case.description}\n"
                        "--- END UNTRUSTED TICKET ---\n\n"
                        f"--- BEGIN UNTRUSTED EXCERPT [{REFERENCE_EXCERPT_ID}] ---\n"
                        f"{REFERENCE_EXCERPT}\n"
                        "--- END UNTRUSTED EXCERPT ---\n\n"
                        f"Citable excerpt identifiers: {REFERENCE_EXCERPT_ID}"
                    ),
                },
            ],
            schema=ReplyDraft,
            max_output_tokens=settings.agent_max_output_tokens,
            timeout_s=settings.llm_timeout_seconds,
        )
        result.draft_chars = len(draft.body)
        result.citations = list(draft.source_citations)
        # "Valid" means every citation refers to something actually supplied. A model inventing
        # a document id is the failure this checks for, and it is the whole point of the field.
        result.citations_valid = all(
            citation == REFERENCE_EXCERPT_ID for citation in draft.source_citations
        )
    except AiProviderError as error:
        result.error_code = str(error.code)

    return result


async def _probe_embeddings(embeddings, results: list[CaseResult]) -> None:
    """Confirms the configured embedding model is reachable and the expected width."""
    try:
        [vector] = await embeddings.embed(["delivery policy"])
        if len(vector) != embeddings.dimensions:
            results.append(
                CaseResult(
                    case_id="embedding-probe",
                    expected_category="-",
                    error_code="AI_DIMENSION_MISMATCH",
                )
            )
    except AiProviderError as error:
        results.append(
            CaseResult(case_id="embedding-probe", expected_category="-", error_code=str(error.code))
        )


def _configured_model(settings: Settings) -> str:
    return settings.llm_model_id or "mock-deterministic"


def _cost_note(settings: Settings, ledger: UsageLedger, cost: Decimal | None) -> str:
    if settings.ai_mode is not AiMode.REAL:
        return "Mock mode: no provider was called, so no cost was incurred."
    if not ledger.fully_reported:
        return (
            "The provider did not report token usage for every call, so totals and cost are "
            "left null rather than estimated."
        )
    if cost is None:
        return (
            "Token counts are the provider's own. Cost is null because "
            "LLM_INPUT_COST_PER_MTOK / LLM_OUTPUT_COST_PER_MTOK are unset — this project does "
            "not ship a price table, because prices change and a stale one reads as fact."
        )
    return "Cost computed from the operator-configured rates and the provider's own token counts."


def write_report(report: EvalReport, directory: Path) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    stamp = report.started_at.replace(":", "-")
    path = directory / f"smoke-{report.mode}-{stamp}.json"
    path.write_text(report.to_json() + "\n", encoding="utf-8")
    return path
