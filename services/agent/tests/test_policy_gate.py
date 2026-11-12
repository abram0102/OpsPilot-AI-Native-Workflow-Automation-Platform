"""The policy gate — the component whose failure would be least visible and most serious.

No database and no model: the gate is ordinary deterministic code, which is the
whole point of it existing separately from the graph.
"""

from __future__ import annotations

import pytest

from opspilot_agent.graphs.policy import (
    ALLOWED_TAGS,
    MAX_PROPOSALS_PER_RUN,
    PolicyDecision,
    PolicyRejectionCode,
    gate,
)
from opspilot_agent.providers.schemas import AgentActionType, ToolProposal

# docs/TASKS.md T28: part of the security regression suite (`make test-security`) —
# the server-side action policy: allowlist, approval requirement, per-run caps.
pytestmark = pytest.mark.security


def tag_proposal(tag: str, tool_call_id: str = "call-1", **arguments: str) -> ToolProposal:
    return ToolProposal(
        tool_call_id=tool_call_id,
        action_type=AgentActionType.ADD_INTERNAL_TAG,
        arguments={"tag": tag, **arguments},
        rationale="because",
    )


def reply_proposal(tool_call_id: str = "call-2", **arguments: str) -> ToolProposal:
    return ToolProposal(
        tool_call_id=tool_call_id,
        action_type=AgentActionType.PUBLISH_INTERNAL_REPLY,
        arguments=dict(arguments),
        rationale="because",
    )


class TestSafeActions:
    @pytest.mark.parametrize("tag", sorted(ALLOWED_TAGS))
    def test_an_allowlisted_tag_may_execute_without_a_human(self, tag: str) -> None:
        [gated] = gate([tag_proposal(tag)])

        assert gated.decision is PolicyDecision.AUTO_EXECUTE
        assert gated.rejection_code is None

    def test_a_well_formed_but_unlisted_tag_is_rejected(self) -> None:
        # Shape alone is not permission: letting a model mint new tags would let it invent its
        # own taxonomy over time.
        [gated] = gate([tag_proposal("escalate-to-ceo")])

        assert gated.decision is PolicyDecision.REJECT
        assert gated.rejection_code is PolicyRejectionCode.TAG_NOT_ALLOWED

    @pytest.mark.parametrize(
        "tag",
        [
            "",
            "Shipping",
            "shipping billing",
            "shipping_billing",
            "-shipping",
            "shipping-",
            "shipping--urgent",
            "<script>alert(1)</script>",
            "shipping; DROP TABLE tickets",
            "../../etc/passwd",
            "a" * 50,
        ],
    )
    def test_a_malformed_tag_is_rejected(self, tag: str) -> None:
        [gated] = gate([tag_proposal(tag)])

        assert gated.decision is PolicyDecision.REJECT
        assert gated.rejection_code in (
            PolicyRejectionCode.INVALID_ARGUMENTS,
            PolicyRejectionCode.TAG_NOT_ALLOWED,
        )

    def test_a_tag_proposal_carrying_extra_arguments_is_rejected(self) -> None:
        [gated] = gate([tag_proposal("shipping", ticket_id="some-other-ticket")])

        assert gated.decision is PolicyDecision.REJECT
        assert gated.rejection_code is PolicyRejectionCode.INVALID_ARGUMENTS


class TestRiskyWrites:
    def test_publishing_a_reply_always_requires_approval(self) -> None:
        # docs/PRODUCT_AND_ARCHITECTURE.md U07: "publishing its drafted response requires
        # ADMIN/OWNER approval". There is no confidence level or category that bypasses this.
        [gated] = gate([reply_proposal()])

        assert gated.decision is PolicyDecision.REQUIRE_APPROVAL

    def test_a_reply_proposal_cannot_smuggle_its_own_body(self) -> None:
        # The content must come from the immutable approved proposal, never from the worker's
        # request — a proposal supplying a body is trying to supply the thing approval pins down.
        [gated] = gate([reply_proposal(body="Here is a refund, enjoy")])

        assert gated.decision is PolicyDecision.REJECT
        assert gated.rejection_code is PolicyRejectionCode.INVALID_ARGUMENTS

    def test_a_reply_proposal_cannot_choose_its_own_approval_id(self) -> None:
        [gated] = gate([reply_proposal(approvalId="00000000-0000-0000-0000-000000000000")])

        assert gated.decision is PolicyDecision.REJECT


class TestBounds:
    def test_proposals_beyond_the_per_run_limit_are_rejected(self) -> None:
        proposals = [
            tag_proposal("shipping", tool_call_id=f"call-{i}")
            for i in range(MAX_PROPOSALS_PER_RUN + 2)
        ]

        gated = gate(proposals)

        assert all(
            item.decision is PolicyDecision.AUTO_EXECUTE for item in gated[:MAX_PROPOSALS_PER_RUN]
        )
        assert all(
            item.rejection_code is PolicyRejectionCode.TOO_MANY_PROPOSALS
            for item in gated[MAX_PROPOSALS_PER_RUN:]
        )

    def test_a_duplicate_tool_call_id_is_rejected(self) -> None:
        # Two proposals sharing a call id would collide on the database's own uniqueness.
        gated = gate([tag_proposal("shipping"), tag_proposal("billing")])

        assert gated[0].decision is PolicyDecision.AUTO_EXECUTE
        assert gated[1].rejection_code is PolicyRejectionCode.DUPLICATE_TOOL_CALL

    def test_an_empty_proposal_list_is_fine(self) -> None:
        assert gate([]) == []


class TestTheGateNeverRaises:
    def test_every_proposal_comes_back_classified(self) -> None:
        # A gate that throws is a gate that can be bypassed by a crash; every proposal must come
        # back with a decision instead.
        proposals = [
            tag_proposal("shipping", tool_call_id="a"),
            tag_proposal("not-a-real-tag", tool_call_id="b"),
            reply_proposal(tool_call_id="c"),
            reply_proposal(tool_call_id="d", body="x"),
        ]

        gated = gate(proposals)

        assert len(gated) == len(proposals)
        assert all(item.decision in set(PolicyDecision) for item in gated)


class TestTheSchemaRefusesUnknownActions:
    """The first line of defence: an action name outside the enum never becomes a proposal."""

    @pytest.mark.parametrize(
        "action_name",
        [
            "DELETE_ALL_TICKETS",
            "RUN_SQL",
            "EXECUTE_SHELL",
            "FETCH_URL",
            "add_internal_tag",
            "",
        ],
    )
    def test_a_model_inventing_an_action_cannot_even_construct_a_proposal(
        self, action_name: str
    ) -> None:
        import pydantic

        with pytest.raises(pydantic.ValidationError):
            ToolProposal(
                tool_call_id="call-1",
                action_type=action_name,  # type: ignore[arg-type]
                arguments={},
                rationale="because",
            )

    def test_a_proposal_cannot_carry_unexpected_fields(self) -> None:
        import pydantic

        with pytest.raises(pydantic.ValidationError):
            ToolProposal(
                tool_call_id="call-1",
                action_type=AgentActionType.ADD_INTERNAL_TAG,
                arguments={"tag": "shipping"},
                rationale="because",
                bypass_approval=True,  # type: ignore[call-arg]
            )
