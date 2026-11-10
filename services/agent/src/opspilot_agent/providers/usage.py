"""Recording what a provider call actually cost.

T20's acceptance says "actual model/date/cost recorded, **not made up**", and
that word governs this whole module.

Two of the three are straightforward: the model id and token counts are read back
from the provider's own response, not from what we asked for — a provider may
serve a different snapshot than the alias requested, and its `usage` block is the
only honest source of token counts (T15's `estimate_tokens` is explicitly a
guard-rail heuristic, never a measurement).

Cost is the one that invites invention. Prices change, differ per region and per
account, and cannot be verified from inside this process. So nothing here
contains a price table: a cost appears **only** when an operator has configured
the rates, and is `None` otherwise. A null cost is a true statement about what we
know; a plausible-looking number would not be.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from decimal import Decimal


@dataclass(frozen=True)
class UsageRecord:
    """One provider call, as the provider itself described it."""

    kind: str
    """`chat` or `embedding`."""

    model: str
    """Echoed back by the provider where it reports one, else the id we asked for."""

    input_tokens: int | None
    output_tokens: int | None
    occurred_at: datetime

    reported_by_provider: bool = False
    """False when the provider returned no `usage` block, so the counts are unknown rather
    than zero. A caller must not sum unknowns into a total and present it as measured."""

    def cost_usd(
        self, input_per_mtok: Decimal | None, output_per_mtok: Decimal | None
    ) -> Decimal | None:
        """Cost at the configured rates, or `None` when any input to the sum is unknown."""
        if not self.reported_by_provider:
            return None
        if input_per_mtok is None or output_per_mtok is None:
            return None
        inputs = Decimal(self.input_tokens or 0) * input_per_mtok / Decimal(1_000_000)
        outputs = Decimal(self.output_tokens or 0) * output_per_mtok / Decimal(1_000_000)
        return (inputs + outputs).quantize(Decimal("0.000001"))


@dataclass
class UsageLedger:
    """Collects every call a run made.

    Passed into a provider rather than queried from it, so usage accumulates per run instead of
    per process and two concurrent runs cannot mix their totals.
    """

    records: list[UsageRecord] = field(default_factory=list)

    def record(self, record: UsageRecord) -> None:
        self.records.append(record)

    @property
    def total_input_tokens(self) -> int:
        return sum(r.input_tokens or 0 for r in self.records if r.reported_by_provider)

    @property
    def total_output_tokens(self) -> int:
        return sum(r.output_tokens or 0 for r in self.records if r.reported_by_provider)

    @property
    def fully_reported(self) -> bool:
        """True only if every call came with real usage. Totals mean nothing otherwise."""
        return bool(self.records) and all(r.reported_by_provider for r in self.records)

    def total_cost_usd(
        self, input_per_mtok: Decimal | None, output_per_mtok: Decimal | None
    ) -> Decimal | None:
        if not self.fully_reported:
            return None
        costs = [r.cost_usd(input_per_mtok, output_per_mtok) for r in self.records]
        if any(cost is None for cost in costs):
            return None
        return sum(costs, Decimal(0))

    def models_used(self) -> list[str]:
        seen: list[str] = []
        for record in self.records:
            if record.model not in seen:
                seen.append(record.model)
        return seen


def usage_from_response(body: dict, *, kind: str, requested_model: str) -> UsageRecord:
    """Reads a provider's `usage` block, honestly reporting when there isn't one.

    The OpenAI-style shape is `{"model": ..., "usage": {"prompt_tokens", "completion_tokens"}}`.
    A provider that omits it leaves the counts unknown — recorded as `None` with
    `reported_by_provider=False`, never silently as zero.
    """
    usage = body.get("usage")
    model = body.get("model") or requested_model

    if not isinstance(usage, dict):
        return UsageRecord(
            kind=kind,
            model=model,
            input_tokens=None,
            output_tokens=None,
            occurred_at=datetime.now(UTC),
            reported_by_provider=False,
        )

    return UsageRecord(
        kind=kind,
        model=model,
        input_tokens=_int_or_none(usage.get("prompt_tokens")),
        output_tokens=_int_or_none(usage.get("completion_tokens")),
        occurred_at=datetime.now(UTC),
        reported_by_provider=True,
    )


def _int_or_none(value: object) -> int | None:
    return value if isinstance(value, int) else None
