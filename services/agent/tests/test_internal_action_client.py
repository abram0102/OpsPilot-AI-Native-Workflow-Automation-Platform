"""The worker's client for the private action API, and the token it mints.

All offline. The service token is checked by decoding it the way the Java side
would; the client is driven through `httpx.MockTransport`.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr

from opspilot_agent.clients.internal_actions import (
    EXECUTE_PATH,
    ActionStatus,
    InternalActionClient,
    InternalActionError,
)
from opspilot_agent.clients.service_token import mint_service_token
from opspilot_agent.config import Settings

SECRET = "a-local-test-secret-long-enough-for-hs256"
AUDIENCE = "https://internal.opspilot.local"


def decode_segment(segment: str) -> dict:
    padded = segment + "=" * (-len(segment) % 4)
    return json.loads(base64.urlsafe_b64decode(padded))


def settings(**overrides) -> Settings:
    base = {
        "internal_service_signing_secret": SecretStr(SECRET),
        "internal_service_audience": AUDIENCE,
        "api_base_url": "https://api.internal",
    }
    base.update(overrides)
    return Settings(**base)


class TestServiceToken:
    def test_it_signs_a_verifiable_hs256_token(self) -> None:
        token = mint_service_token(secret=SECRET, audience=AUDIENCE)
        header_b64, claims_b64, signature_b64 = token.split(".")

        # Verified the way the Java side will: recompute the MAC over the signing input.
        expected = hmac.new(
            SECRET.encode(), f"{header_b64}.{claims_b64}".encode("ascii"), hashlib.sha256
        ).digest()
        padded = signature_b64 + "=" * (-len(signature_b64) % 4)

        assert base64.urlsafe_b64decode(padded) == expected
        assert decode_segment(header_b64) == {"alg": "HS256", "typ": "JWT"}

    def test_it_claims_the_service_audience_and_a_short_life(self) -> None:
        token = mint_service_token(secret=SECRET, audience=AUDIENCE, ttl_seconds=300)
        claims = decode_segment(token.split(".")[1])

        assert claims["aud"] == AUDIENCE
        assert claims["sub"] == "opspilot-agent-worker"
        # Short-lived on purpose: this token authorises user-visible writes.
        assert 0 < claims["exp"] - claims["iat"] <= 310

    def test_the_segments_carry_no_padding(self) -> None:
        # JWS requires base64url without '='; a padded segment is rejected by strict parsers.
        assert "=" not in mint_service_token(secret=SECRET, audience=AUDIENCE)

    def test_it_refuses_to_sign_without_a_secret(self) -> None:
        with pytest.raises(ValueError, match="signing secret"):
            mint_service_token(secret="", audience=AUDIENCE)


class TestInternalActionClient:
    def _client(self, handler) -> InternalActionClient:
        return InternalActionClient(
            settings(),
            client=httpx.AsyncClient(
                transport=httpx.MockTransport(handler), base_url="https://api.internal"
            ),
        )

    async def test_it_sends_only_the_fields_the_endpoint_accepts(self) -> None:
        seen: dict = {}

        def handler(request: httpx.Request) -> httpx.Response:
            seen["path"] = request.url.path
            seen["auth"] = request.headers.get("Authorization")
            seen["body"] = json.loads(request.content)
            return httpx.Response(
                200,
                json={
                    "toolCallId": "call-1",
                    "status": "SUCCEEDED",
                    "resultReference": "ticket_message:1",
                    "alreadyApplied": False,
                },
            )

        run_id = uuid4()
        await self._client(handler).execute(
            run_id=run_id,
            tool_call_id="call-1",
            action_type="PUBLISH_INTERNAL_REPLY",
            arguments={"approvalId": "abc"},
        )

        assert seen["path"] == EXECUTE_PATH
        assert seen["auth"].startswith("Bearer ")
        # No organizationId or ticketId: the endpoint has no such fields, which is what makes the
        # worker's claims untrusted by construction rather than by discipline.
        assert set(seen["body"]) == {"runId", "toolCallId", "actionType", "arguments"}
        assert seen["body"]["runId"] == str(run_id)

    async def test_it_reports_a_successful_outcome(self) -> None:
        outcome = await self._client(
            lambda _r: httpx.Response(
                200,
                json={
                    "toolCallId": "call-1",
                    "status": "SUCCEEDED",
                    "resultReference": "ticket_message:7",
                    "alreadyApplied": False,
                },
            )
        ).execute(
            run_id=uuid4(),
            tool_call_id="call-1",
            action_type="PUBLISH_INTERNAL_REPLY",
            arguments={},
        )

        assert outcome.succeeded
        assert outcome.result_reference == "ticket_message:7"

    async def test_a_rejection_is_an_answer_not_an_error(self) -> None:
        # REJECTED means policy refused it — a normal outcome the worker records, not a failure.
        outcome = await self._client(
            lambda _r: httpx.Response(
                200,
                json={
                    "toolCallId": "call-1",
                    "status": "REJECTED",
                    "resultReference": None,
                    "alreadyApplied": False,
                },
            )
        ).execute(
            run_id=uuid4(), tool_call_id="call-1", action_type="ADD_INTERNAL_TAG", arguments={}
        )

        assert outcome.status is ActionStatus.REJECTED
        assert not outcome.succeeded

    async def test_a_replay_is_reported_as_already_applied(self) -> None:
        outcome = await self._client(
            lambda _r: httpx.Response(
                200,
                json={
                    "toolCallId": "call-1",
                    "status": "SUCCEEDED",
                    "resultReference": "ticket_message:7",
                    "alreadyApplied": True,
                },
            )
        ).execute(
            run_id=uuid4(), tool_call_id="call-1", action_type="ADD_INTERNAL_TAG", arguments={}
        )

        assert outcome.already_applied

    @pytest.mark.parametrize("status_code", [401, 403])
    async def test_a_refused_identity_is_an_error(self, status_code: int) -> None:
        with pytest.raises(InternalActionError, match="identity"):
            await self._client(lambda _r: httpx.Response(status_code)).execute(
                run_id=uuid4(), tool_call_id="c", action_type="ADD_INTERNAL_TAG", arguments={}
            )

    async def test_a_server_error_is_an_error(self) -> None:
        with pytest.raises(InternalActionError):
            await self._client(lambda _r: httpx.Response(500)).execute(
                run_id=uuid4(), tool_call_id="c", action_type="ADD_INTERNAL_TAG", arguments={}
            )

    async def test_an_unreachable_api_is_an_error(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("no route", request=request)

        with pytest.raises(InternalActionError, match="Could not reach"):
            await self._client(handler).execute(
                run_id=uuid4(), tool_call_id="c", action_type="ADD_INTERNAL_TAG", arguments={}
            )

    async def test_an_unrecognised_body_is_an_error(self) -> None:
        with pytest.raises(InternalActionError, match="does not understand"):
            await self._client(lambda _r: httpx.Response(200, json={"unexpected": True})).execute(
                run_id=uuid4(), tool_call_id="c", action_type="ADD_INTERNAL_TAG", arguments={}
            )

    async def test_it_refuses_to_call_without_a_configured_secret(self) -> None:
        client = InternalActionClient(
            settings(internal_service_signing_secret=None),
            client=httpx.AsyncClient(
                transport=httpx.MockTransport(lambda _r: httpx.Response(200)),
                base_url="https://api.internal",
            ),
        )

        with pytest.raises(InternalActionError, match="not configured"):
            await client.execute(
                run_id=uuid4(), tool_call_id="c", action_type="ADD_INTERNAL_TAG", arguments={}
            )
