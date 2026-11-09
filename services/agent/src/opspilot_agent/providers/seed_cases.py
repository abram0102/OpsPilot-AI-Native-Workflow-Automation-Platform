"""Synthetic cases that seed the deterministic mock provider.

These are keyword rules, not a model and not a benchmark. Their job is to make
`AI_MODE=mock` produce output that is *reproducible* and *plausibly shaped*, so
tests for the graph (T19) and the indexer (T16) can assert on real values
instead of placeholders — while never calling a provider or needing a key.

Deliberately NOT the evaluation dataset. docs/SECURITY_AND_TESTING.md section 5
requires `evals/datasets/support_triage_v1.jsonl` with 60+ cases; that file and
the scoring around it are T30's, and building it here would be implementing a
future ticket against rules that have not been designed yet.

All text is fictional.
"""

from __future__ import annotations

from dataclasses import dataclass

from opspilot_agent.providers.schemas import TicketCategory, Urgency


@dataclass(frozen=True)
class CategoryRule:
    category: TicketCategory
    keywords: tuple[str, ...]


CATEGORY_RULES: tuple[CategoryRule, ...] = (
    CategoryRule(
        TicketCategory.SHIPPING,
        ("shipping", "shipment", "delivery", "deliver", "tracking", "parcel", "package", "courier"),
    ),
    CategoryRule(
        TicketCategory.BILLING,
        ("billing", "invoice", "refund", "charge", "charged", "payment", "price", "subscription"),
    ),
    CategoryRule(
        TicketCategory.ACCOUNT,
        ("account", "login", "log in", "sign in", "password", "locked out", "two-factor", "email"),
    ),
)
"""Checked in order; the rule with the most keyword hits wins, OTHER if nothing matches."""


HIGH_URGENCY_KEYWORDS: tuple[str, ...] = (
    "urgent",
    "asap",
    "immediately",
    "emergency",
    "outage",
    "cannot access",
    "lost",
    "fraud",
    "unauthorized",
)

LOW_URGENCY_KEYWORDS: tuple[str, ...] = (
    "question",
    "wondering",
    "curious",
    "whenever",
    "no rush",
    "just checking",
)


def classify_category(text: str) -> tuple[TicketCategory, int]:
    """Returns the best-matching category and how many keywords supported it."""
    lowered = text.lower()
    best_category = TicketCategory.OTHER
    best_hits = 0
    for rule in CATEGORY_RULES:
        hits = sum(1 for keyword in rule.keywords if keyword in lowered)
        if hits > best_hits:
            best_category, best_hits = rule.category, hits
    return best_category, best_hits


def classify_urgency(text: str) -> Urgency:
    lowered = text.lower()
    if any(keyword in lowered for keyword in HIGH_URGENCY_KEYWORDS):
        return Urgency.HIGH
    if any(keyword in lowered for keyword in LOW_URGENCY_KEYWORDS):
        return Urgency.LOW
    return Urgency.MEDIUM
