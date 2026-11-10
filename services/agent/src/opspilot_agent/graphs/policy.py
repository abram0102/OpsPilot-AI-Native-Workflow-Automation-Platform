"""The policy gate: deterministic rules a model cannot influence.

This is the security core of the graph. AGENTS.md: "AI cannot choose which
permissions it has", and "All user-visible writes from an AI agent pass server
policy: role checks, allowlisted action, target resource matches the run tenant,
idempotency, and human approval for risky writes."

Everything here is ordinary code with no model in the loop. A proposal arrives
as data and leaves classified; nothing a document or a ticket says can change
which branch it takes. The gate is written to be boring on purpose — it is the
component whose failure would be least visible and most serious.

Two actions exist, and they are treated differently because the product says so
(docs/PRODUCT_AND_ARCHITECTURE.md U07): "Agent can automatically add a safe
internal tag if policy allows; publishing its drafted response requires
ADMIN/OWNER approval."
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import StrEnum

from opspilot_agent.providers.schemas import AgentActionType, ToolProposal

MAX_TAG_LENGTH = 40
MAX_PROPOSALS_PER_RUN = 5
"""A bound on how much a single run may propose, so a confused model cannot flood a reviewer."""

# Deterministic tag rules (docs/API_CONTRACT.md: "after deterministic tag allowlist/length
# limits"). Lowercase words and single hyphens only — no whitespace, punctuation or markup that
# could travel somewhere that interprets it.
_TAG_PATTERN = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")

ALLOWED_TAGS = frozenset(
    {
        "shipping",
        "billing",
        "account",
        "other",
        "urgent",
        "needs-human",
        "awaiting-customer",
    }
)
"""An allowlist rather than a pattern check alone: a *valid-looking* tag is still not a permitted
one, and letting a model mint new tags would let it invent its own taxonomy over time."""


class PolicyDecision(StrEnum):
    AUTO_EXECUTE = "AUTO_EXECUTE"
    """A safe action policy permits without a human — today, only an allowlisted internal tag."""

    REQUIRE_APPROVAL = "REQUIRE_APPROVAL"
    """A user-visible write. A human approves the exact proposal before anything happens."""

    REJECT = "REJECT"
    """The proposal is not permissible at all and is recorded as blocked."""


class PolicyRejectionCode(StrEnum):
    UNKNOWN_ACTION = "POLICY_UNKNOWN_ACTION"
    INVALID_ARGUMENTS = "POLICY_INVALID_ARGUMENTS"
    TAG_NOT_ALLOWED = "POLICY_TAG_NOT_ALLOWED"
    TOO_MANY_PROPOSALS = "POLICY_TOO_MANY_PROPOSALS"
    DUPLICATE_TOOL_CALL = "POLICY_DUPLICATE_TOOL_CALL"


@dataclass(frozen=True)
class GatedProposal:
    proposal: ToolProposal
    decision: PolicyDecision
    rejection_code: PolicyRejectionCode | None = None
    reason: str | None = None


def gate(proposals: list[ToolProposal]) -> list[GatedProposal]:
    """Classifies each proposal. Never raises: a bad proposal is a recorded rejection, not a crash.

    Order matters only for the count limit, which applies to proposals as offered.
    """
    gated: list[GatedProposal] = []
    seen_tool_calls: set[str] = set()

    for index, proposal in enumerate(proposals):
        if index >= MAX_PROPOSALS_PER_RUN:
            gated.append(
                _reject(
                    proposal,
                    PolicyRejectionCode.TOO_MANY_PROPOSALS,
                    f"A run may propose at most {MAX_PROPOSALS_PER_RUN} actions.",
                )
            )
            continue

        if proposal.tool_call_id in seen_tool_calls:
            # Two proposals sharing a call id would collide on the database's own
            # (run_id, tool_call_id) uniqueness; catching it here keeps the reason legible.
            gated.append(
                _reject(
                    proposal,
                    PolicyRejectionCode.DUPLICATE_TOOL_CALL,
                    "Each proposed action needs its own tool_call_id.",
                )
            )
            continue
        seen_tool_calls.add(proposal.tool_call_id)

        gated.append(_classify(proposal))

    return gated


def _classify(proposal: ToolProposal) -> GatedProposal:
    if proposal.action_type is AgentActionType.ADD_INTERNAL_TAG:
        return _classify_tag(proposal)
    if proposal.action_type is AgentActionType.PUBLISH_INTERNAL_REPLY:
        return _classify_publish(proposal)

    # Unreachable while AgentActionType has exactly these two members, but a new member added
    # without a rule here must fail closed rather than fall through to something permissive.
    return _reject(
        proposal,
        PolicyRejectionCode.UNKNOWN_ACTION,
        "No policy rule covers this action type.",
    )


def _classify_tag(proposal: ToolProposal) -> GatedProposal:
    tag = proposal.arguments.get("tag", "")
    if not tag or len(tag) > MAX_TAG_LENGTH or not _TAG_PATTERN.match(tag):
        return _reject(
            proposal,
            PolicyRejectionCode.INVALID_ARGUMENTS,
            "A tag must be lowercase words separated by single hyphens.",
        )
    if set(proposal.arguments) != {"tag"}:
        return _reject(
            proposal,
            PolicyRejectionCode.INVALID_ARGUMENTS,
            "ADD_INTERNAL_TAG takes exactly one argument.",
        )
    if tag not in ALLOWED_TAGS:
        return _reject(
            proposal,
            PolicyRejectionCode.TAG_NOT_ALLOWED,
            "That tag is not on the allowlist.",
        )
    return GatedProposal(proposal, PolicyDecision.AUTO_EXECUTE)


def _classify_publish(proposal: ToolProposal) -> GatedProposal:
    # docs/API_CONTRACT.md: PUBLISH_INTERNAL_REPLY takes `{approvalId}` only, because the content
    # comes "from immutable approved proposal, not worker request". A proposal that tries to carry
    # a body is trying to supply the very thing approval exists to pin down.
    if proposal.arguments:
        return _reject(
            proposal,
            PolicyRejectionCode.INVALID_ARGUMENTS,
            "A reply proposal carries no arguments; its content is the approved draft.",
        )
    return GatedProposal(proposal, PolicyDecision.REQUIRE_APPROVAL)


def _reject(proposal: ToolProposal, code: PolicyRejectionCode, reason: str) -> GatedProposal:
    return GatedProposal(proposal, PolicyDecision.REJECT, code, reason)
