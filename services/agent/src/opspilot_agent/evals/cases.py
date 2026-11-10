"""The three fixed synthetic tickets T20's smoke evaluation runs.

Fixed, few, and fictional. T20's acceptance names "3 fixed synthetic tickets",
and the point of fixing them is that two runs are comparable: a changed prompt or
model shows up as a difference in the output rather than being lost in different
inputs.

These are a *smoke* set — enough to prove a real provider returns typed output
with valid citations. The scored 60+ case dataset is T30's
(`evals/datasets/support_triage_v1.jsonl`), and building it here would be
implementing that ticket.

Every detail is invented. No real customer, order or person is described.
"""

from __future__ import annotations

from dataclasses import dataclass

from opspilot_agent.providers.schemas import TicketCategory


@dataclass(frozen=True)
class EvalCase:
    case_id: str
    subject: str
    description: str
    expected_category: TicketCategory
    """What a competent triage should pick. Recorded for comparison, not asserted against a real
    provider — a single wrong category is a signal to look, not a test failure."""


SMOKE_CASES: tuple[EvalCase, ...] = (
    EvalCase(
        case_id="smoke-shipping-1",
        subject="Where is my delivery?",
        description=(
            "I ordered a desk lamp eight days ago and the tracking page has not updated since "
            "it left the warehouse. Has the parcel been lost?"
        ),
        expected_category=TicketCategory.SHIPPING,
    ),
    EvalCase(
        case_id="smoke-billing-1",
        subject="Charged twice for one order",
        description=(
            "My statement shows two identical charges for the same order placed last Tuesday. "
            "I only ordered once and would like the duplicate refunded."
        ),
        expected_category=TicketCategory.BILLING,
    ),
    EvalCase(
        case_id="smoke-account-1",
        subject="Cannot sign in after password reset",
        description=(
            "I requested a password reset yesterday, set a new password, and still cannot sign "
            "in. The page says my credentials are not recognised."
        ),
        expected_category=TicketCategory.ACCOUNT,
    ),
)
