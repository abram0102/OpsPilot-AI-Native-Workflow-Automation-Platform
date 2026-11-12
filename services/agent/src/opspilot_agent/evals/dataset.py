"""Loading and validating `evals/datasets/support_triage_v1.jsonl` (docs/TASKS.md T30).

The dataset is JSONL on disk rather than Python objects for one reason: it is data, and it should
be diffable, countable and editable without touching code. The validation here exists because a
dataset that silently loses half its rows to a typo would still produce a confident-looking report.
"""

from __future__ import annotations

import hashlib
import json
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

from opspilot_agent.providers.schemas import AgentActionType, TicketCategory, Urgency

DATASET_VERSION = "support_triage_v1"


def _find_repository_dataset() -> Path:
    """Walks up from this file looking for `evals/datasets/`.

    Not a fixed `parents[n]` index: that is five directories of coupling to the repository layout
    expressed as a number, and it is wrong the moment anything moves. It was wrong on the first
    run here, resolving to `services/evals/`.
    """
    relative = Path("evals") / "datasets" / "support_triage_v1.jsonl"
    for ancestor in Path(__file__).resolve().parents:
        candidate = ancestor / relative
        if candidate.exists():
            return candidate
    # Nothing found: return the conventional location so the caller's "not found" error names a
    # sensible path rather than this function failing with something more obscure.
    return Path(__file__).resolve().parents[5] / relative


DEFAULT_DATASET_PATH = _find_repository_dataset()


class DatasetError(ValueError):
    """The dataset on disk is not usable. Always fatal — never scored around."""


@dataclass(frozen=True)
class TriageCase:
    case_id: str
    split: str
    subject: str
    body: str
    expected_category: TicketCategory
    expected_urgency: Urgency
    allowed_actions: tuple[AgentActionType, ...]
    requires_approval: bool
    expected_document_ids: tuple[str, ...]
    expected_behavior: str
    adversarial: bool
    notes: str | None

    @property
    def ticket_text(self) -> str:
        return f"Subject: {self.subject}\n\n{self.body}"


@dataclass(frozen=True)
class Dataset:
    version: str
    path: Path
    cases: tuple[TriageCase, ...]
    #: SHA-256 of the file's bytes. Stamped into every report, so two reports can be compared
    #: without having to wonder whether the inputs were the same.
    digest: str

    def by_split(self, split: str) -> tuple[TriageCase, ...]:
        return tuple(case for case in self.cases if case.split == split)

    @property
    def adversarial(self) -> tuple[TriageCase, ...]:
        return tuple(case for case in self.cases if case.adversarial)

    def composition(self) -> dict[str, int]:
        counts = Counter(case.expected_category.value for case in self.cases)
        counts["ADVERSARIAL"] = len(self.adversarial)
        return dict(sorted(counts.items()))


_REQUIRED_FIELDS = {
    "case_id",
    "split",
    "subject",
    "body",
    "expected_category",
    "expected_urgency",
    "allowed_actions",
    "requires_approval",
    "expected_document_ids",
    "expected_behavior",
    "adversarial",
}

_VALID_SPLITS = {"baseline", "heldout"}

#: docs/SECURITY_AND_TESTING.md §5 asks for "at least 60" entries. Asserted on load, because a
#: dataset that quietly shrank would still score — just over fewer cases, with a headline number
#: that looks the same.
MINIMUM_CASES = 60

#: §5 also asks specifically for adversarial / permission-boundary coverage. These are the cases
#: the forbidden-action metric is computed over, so a dataset without them would report a
#: flawless zero while testing nothing.
MINIMUM_ADVERSARIAL = 10


def load_dataset(path: Path | None = None) -> Dataset:
    source = path or DEFAULT_DATASET_PATH
    if not source.exists():
        raise DatasetError(f"dataset not found at {source}")

    raw = source.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()

    cases: list[TriageCase] = []
    seen: set[str] = set()

    for number, line in enumerate(raw.decode("utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError as error:
            raise DatasetError(f"{source.name} line {number}: not valid JSON — {error}") from error

        missing = _REQUIRED_FIELDS - record.keys()
        if missing:
            raise DatasetError(f"{source.name} line {number}: missing {', '.join(sorted(missing))}")
        if record["case_id"] in seen:
            raise DatasetError(
                f"{source.name} line {number}: duplicate case_id {record['case_id']}"
            )
        seen.add(record["case_id"])
        if record["split"] not in _VALID_SPLITS:
            raise DatasetError(
                f"{source.name} line {number}: split must be one of {sorted(_VALID_SPLITS)}"
            )

        try:
            cases.append(
                TriageCase(
                    case_id=record["case_id"],
                    split=record["split"],
                    subject=record["subject"],
                    body=record["body"],
                    expected_category=TicketCategory(record["expected_category"]),
                    expected_urgency=Urgency(record["expected_urgency"]),
                    allowed_actions=tuple(
                        AgentActionType(action) for action in record["allowed_actions"]
                    ),
                    requires_approval=bool(record["requires_approval"]),
                    expected_document_ids=tuple(record["expected_document_ids"]),
                    expected_behavior=record["expected_behavior"],
                    adversarial=bool(record["adversarial"]),
                    notes=record.get("notes"),
                )
            )
        except ValueError as error:
            # A category or action that is not in the enum. Fatal rather than skipped: the enums
            # are the contract with the running system, and a dataset naming an action that does
            # not exist is measuring something this product cannot do.
            raise DatasetError(f"{source.name} line {number}: {error}") from error

    if len(cases) < MINIMUM_CASES:
        raise DatasetError(
            f"{source.name} has {len(cases)} cases; docs/SECURITY_AND_TESTING.md §5 requires at "
            f"least {MINIMUM_CASES}"
        )
    adversarial = sum(1 for case in cases if case.adversarial)
    if adversarial < MINIMUM_ADVERSARIAL:
        raise DatasetError(
            f"{source.name} has {adversarial} adversarial cases; at least {MINIMUM_ADVERSARIAL} "
            "are required, because they are what the forbidden-action metric is computed over"
        )

    # Sorted by case_id, so the report's case order does not depend on file order. One of the
    # several things that have to be true for two runs to be byte-comparable.
    cases.sort(key=lambda case: case.case_id)
    return Dataset(version=DATASET_VERSION, path=source, cases=tuple(cases), digest=digest)
