"""Minting the worker's machine identity for the private internal API.

HS256 over the shared `INTERNAL_SERVICE_SIGNING_SECRET`, matching what
`services/api`'s `InternalServiceSecurityConfig` validates.

Hand-rolled rather than adding a JWT dependency, deliberately: signing is about
fifteen lines of stdlib `hmac`/`base64`, this service needs no other JWT
behaviour, and the hard direction — *verification* — stays in Java where
Spring Security does it. The Java side's own test signer is hand-rolled for the
same reason. Adding a library here would mean a dependency decision for code
that would still only ever call one function.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time


def mint_service_token(
    *, secret: str, audience: str, subject: str = "opspilot-agent-worker", ttl_seconds: int = 300
) -> str:
    """A short-lived HS256 token for the internal action API.

    Short-lived on purpose: this token authorises user-visible writes, so it is minted per call
    rather than held, and a leaked one expires in minutes rather than being valid indefinitely.
    """
    if not secret:
        raise ValueError(
            "No internal service signing secret is configured; refusing to mint a token."
        )

    issued_at = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    claims = {
        "sub": subject,
        "aud": audience,
        # Backdated slightly, as the Java test signer does, so small clock differences between
        # the worker and the API do not reject an otherwise valid token.
        "iat": issued_at - 5,
        "exp": issued_at + ttl_seconds,
    }

    signing_input = f"{_segment(header)}.{_segment(claims)}".encode("ascii")
    signature = hmac.new(secret.encode("utf-8"), signing_input, hashlib.sha256).digest()
    return f"{signing_input.decode('ascii')}.{_b64(signature)}"


def _segment(payload: dict) -> str:
    return _b64(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8"))


def _b64(raw: bytes) -> str:
    """base64url without padding, as JWS requires."""
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")
