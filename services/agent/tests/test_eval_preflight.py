"""The gate in front of paid provider calls, and the usage/cost recording behind it.

Every test here runs offline. Nothing in this file may make a billable call —
which is also the property the first test actually asserts.
"""

from __future__ import annotations

from datetime import UTC, datetime
from decimal import Decimal

import httpx
import pytest
from pydantic import SecretStr

from opspilot_agent.config import AiMode, Settings
from opspilot_agent.evals import __main__ as eval_cli
from opspilot_agent.evals.preflight import check_real_eval_allowed, refusal_message
from opspilot_agent.evals.smoke import run_smoke_eval
from opspilot_agent.providers.http import HttpLLMProvider
from opspilot_agent.providers.resilience import CircuitBreaker, RetryPolicy
from opspilot_agent.providers.schemas import Classification
from opspilot_agent.providers.usage import UsageLedger, UsageRecord, usage_from_response


def fully_configured() -> Settings:
    """Everything Gate H2 asks for. Used to prove the gate *opens*, never to call anything."""
    return Settings(
        ai_mode=AiMode.REAL,
        allow_real_llm_calls=True,
        llm_api_key=SecretStr("sk-test-only-not-a-real-key"),
        llm_model_id="a-chat-model",
        embedding_model_id="an-embedding-model",
    )


class TestPreflightRefuses:
    def test_mock_mode_is_refused(self) -> None:
        result = check_real_eval_allowed(Settings())

        assert result.refused
        assert any("AI_MODE" in reason for reason in result.reasons)

    def test_real_mode_without_the_explicit_flag_is_refused(self) -> None:
        """The whole point of the second lock: AI_MODE=real must not be enough on its own."""
        settings = fully_configured().model_copy(update={"allow_real_llm_calls": False})

        result = check_real_eval_allowed(settings)

        assert result.refused
        assert any("ALLOW_REAL_LLM_CALLS" in reason for reason in result.reasons)

    @pytest.mark.parametrize(
        ("field", "value", "expected"),
        [
            ("llm_api_key", None, "LLM_API_KEY"),
            ("llm_model_id", None, "LLM_MODEL_ID"),
            ("embedding_model_id", None, "EMBEDDING_MODEL_ID"),
            ("eval_max_cases", 0, "EVAL_MAX_CASES"),
        ],
    )
    def test_each_missing_requirement_is_named(
        self, field: str, value: object, expected: str
    ) -> None:
        settings = fully_configured().model_copy(update={field: value})

        result = check_real_eval_allowed(settings)

        assert result.refused
        assert any(expected in reason for reason in result.reasons)

    def test_every_missing_requirement_is_reported_at_once(self) -> None:
        # An operator setting this up deserves the whole checklist, not three consecutive
        # failures each revealing one more requirement.
        result = check_real_eval_allowed(Settings())

        assert len(result.reasons) >= 4

    def test_the_refusal_message_explains_the_setup_and_the_cap(self) -> None:
        settings = Settings()
        message = refusal_message(check_real_eval_allowed(settings), settings)

        assert "Gate H2" in message
        assert "docs/MANUAL_SETUP.md" in message
        assert "make eval-mock" in message
        assert str(settings.eval_max_cases) in message


class TestPreflightAllows:
    def test_a_fully_configured_environment_passes(self) -> None:
        # Asserts the gate can open — without calling anything, because preflight is pure.
        result = check_real_eval_allowed(fully_configured())

        assert result.allowed
        assert result.reasons == ()


class TestTheCliRefusesInBothDirections:
    def test_real_without_gate_h2_exits_non_zero(self, capsys) -> None:
        exit_code = eval_cli.main(["--real"])

        assert exit_code == 2
        assert "Refusing to run" in capsys.readouterr().err

    def test_offline_run_in_a_real_mode_environment_is_refused(
        self, monkeypatch: pytest.MonkeyPatch, capsys
    ) -> None:
        """The opposite mistake: a real-mode environment running the offline-looking eval."""
        monkeypatch.setattr(eval_cli, "get_settings", lambda: fully_configured())

        exit_code = eval_cli.main([])

        assert exit_code == 2
        assert "Refusing to guess" in capsys.readouterr().err


class TestUsageIsReadFromTheProvider:
    def test_token_counts_come_from_the_response_not_our_estimate(self) -> None:
        record = usage_from_response(
            {"model": "served-model-v2", "usage": {"prompt_tokens": 111, "completion_tokens": 22}},
            kind="chat",
            requested_model="requested-model",
        )

        assert record.input_tokens == 111
        assert record.output_tokens == 22
        # A provider may serve a different snapshot than the alias asked for; record what it says.
        assert record.model == "served-model-v2"
        assert record.reported_by_provider

    def test_a_missing_usage_block_is_unknown_rather_than_zero(self) -> None:
        record = usage_from_response({"model": "m"}, kind="chat", requested_model="requested-model")

        assert record.input_tokens is None
        assert not record.reported_by_provider

    async def test_the_adapter_records_usage_from_a_real_response_shape(self) -> None:
        ledger = UsageLedger()

        def handler(_request: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200,
                json={
                    "model": "served-model-v2",
                    "usage": {"prompt_tokens": 90, "completion_tokens": 10},
                    "choices": [
                        {
                            "message": {
                                "content": (
                                    '{"category":"BILLING","urgency":"LOW",'
                                    '"summary":"s","confidence":0.5}'
                                )
                            }
                        }
                    ],
                },
            )

        provider = HttpLLMProvider(
            client=httpx.AsyncClient(
                transport=httpx.MockTransport(handler), base_url="https://provider"
            ),
            model_id="requested-model",
            api_key=SecretStr("sk-test-only"),
            retry=RetryPolicy(max_attempts=1),
            breaker=CircuitBreaker(threshold=5, reset_after_s=60),
            max_input_tokens=8000,
            usage_ledger=ledger,
        )

        await provider.structured_response(
            messages=[{"role": "user", "content": "hi"}],
            schema=Classification,
            max_output_tokens=100,
            timeout_s=5,
        )

        assert ledger.total_input_tokens == 90
        assert ledger.models_used() == ["served-model-v2"]


class TestCostIsNeverInvented:
    def _reported(self, prompt: int, completion: int) -> UsageRecord:
        return UsageRecord(
            kind="chat",
            model="m",
            input_tokens=prompt,
            output_tokens=completion,
            occurred_at=datetime.now(UTC),
            reported_by_provider=True,
        )

    def test_no_configured_rates_means_a_null_cost(self) -> None:
        ledger = UsageLedger([self._reported(1_000_000, 1_000_000)])

        assert ledger.total_cost_usd(None, None) is None

    def test_configured_rates_produce_a_real_figure(self) -> None:
        ledger = UsageLedger([self._reported(1_000_000, 500_000)])

        cost = ledger.total_cost_usd(Decimal("3.00"), Decimal("15.00"))

        assert cost == Decimal("10.500000")

    def test_unreported_usage_blocks_any_total(self) -> None:
        # Summing unknowns and presenting the result as measured would be exactly the fabricated
        # metric AGENTS.md rule 6 forbids.
        ledger = UsageLedger(
            [
                self._reported(100, 10),
                UsageRecord(
                    kind="chat",
                    model="m",
                    input_tokens=None,
                    output_tokens=None,
                    occurred_at=datetime.now(UTC),
                    reported_by_provider=False,
                ),
            ]
        )

        assert not ledger.fully_reported
        assert ledger.total_cost_usd(Decimal("3.00"), Decimal("15.00")) is None


class TestOfflineSmokeEval:
    async def test_it_runs_with_no_key_and_reports_no_cost(self) -> None:
        report = await run_smoke_eval(Settings(), max_cases=3)

        assert report.mode == "mock"
        assert len(report.cases) == 3
        assert report.all_ok
        assert report.total_cost_usd is None
        assert "no cost was incurred" in report.cost_note

    async def test_the_case_cap_is_respected(self) -> None:
        report = await run_smoke_eval(Settings(), max_cases=1)

        assert len(report.cases) == 1

    async def test_it_records_each_cases_observed_classification(self) -> None:
        report = await run_smoke_eval(Settings(), max_cases=3)

        observed = {case.case_id: case.observed_category for case in report.cases}
        assert observed["smoke-shipping-1"] == "SHIPPING"
        assert observed["smoke-billing-1"] == "BILLING"
        assert observed["smoke-account-1"] == "ACCOUNT"
