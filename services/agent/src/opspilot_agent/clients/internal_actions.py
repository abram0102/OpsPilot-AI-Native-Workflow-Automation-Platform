"""The worker's client for `POST /internal/v1/agent-actions/execute` (docs/TASKS.md T23).

This is the one place this service asks another to make a user-visible change.
Everything it sends is a *request*, not an instruction: the API reconstructs the
organization, ticket and approval state from the persisted run and re-checks
policy, so a bug here cannot widen what the agent is allowed to do — it can only
fail to get something done.

The request deliberately carries no organization or ticket id. There are no such
fields on the endpoint (T21/ADR-045), which is what makes "the worker's claims
are untrusted hints" true by construction rather than by discipline.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from uuid import UUID

import httpx

from opspilot_agent.clients.service_token import mint_service_token
from opspilot_agent.config import Settings

EXECUTE_PATH = "/internal/v1/agent-actions/execute"


class ActionStatus(StrEnum):
    SUCCEEDED = "SUCCEEDED"
    REJECTED = "REJECTED"
    FAILED = "FAILED"


class InternalActionError(Exception):
    """The call could not be completed. Distinct from a REJECTED outcome, which is an answer."""


@dataclass(frozen=True)
class ActionOutcome:
    tool_call_id: str
    status: ActionStatus
    result_reference: str | None
    already_applied: bool

    @property
    def succeeded(self) -> bool:
        return self.status is ActionStatus.SUCCEEDED


class InternalActionClient:
    """Calls the private action API with a freshly minted service token.

    The `httpx.AsyncClient` is injectable for the same reason T15's provider adapter's is: it lets
    the tests drive every path — success, rejection, failure, transport error — without a running
    API, which is the only option here since nothing in this repository runs both services
    together.
    """

    def __init__(self, settings: Settings, *, client: httpx.AsyncClient | None = None) -> None:
        self._settings = settings
        self._client = client

    async def execute(
        self,
        *,
        run_id: UUID,
        tool_call_id: str,
        action_type: str,
        arguments: dict[str, str],
    ) -> ActionOutcome:
        secret = self._settings.internal_service_signing_secret
        if secret is None or not secret.get_secret_value():
            raise InternalActionError(
                "INTERNAL_SERVICE_SIGNING_SECRET is not configured; the worker cannot "
                "authenticate to the internal action API."
            )

        token = mint_service_token(
            secret=secret.get_secret_value(),
            audience=self._settings.internal_service_audience,
            ttl_seconds=self._settings.internal_service_token_ttl_seconds,
        )

        payload = {
            "runId": str(run_id),
            "toolCallId": tool_call_id,
            "actionType": action_type,
            "arguments": arguments,
        }

        client = self._client or httpx.AsyncClient(base_url=self._settings.api_base_url)
        try:
            response = await client.post(
                EXECUTE_PATH,
                json=payload,
                headers={
                    "Authorization": f"Bearer {token}",
                    "Content-Type": "application/json",
                },
                timeout=self._settings.agent_tool_timeout_seconds,
            )
        except httpx.HTTPError as error:
            raise InternalActionError(
                f"Could not reach the internal action API: {type(error).__name__}."
            ) from error

        if response.status_code == 401 or response.status_code == 403:
            # The worker's identity was refused. Retrying with the same credentials cannot help,
            # and the reason is deliberately not detailed by the API.
            raise InternalActionError(
                f"The internal action API refused this worker's identity ({response.status_code})."
            )
        if response.status_code >= 400:
            raise InternalActionError(f"The internal action API returned {response.status_code}.")

        try:
            body = response.json()
            return ActionOutcome(
                tool_call_id=body["toolCallId"],
                status=ActionStatus(body["status"]),
                result_reference=body.get("resultReference"),
                already_applied=bool(body.get("alreadyApplied")),
            )
        except (KeyError, ValueError) as error:
            raise InternalActionError(
                "The internal action API returned a body this client does not understand."
            ) from error
