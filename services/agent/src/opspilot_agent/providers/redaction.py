"""Keeping credentials out of logs, traces and exception messages.

docs/SECURITY_AND_TESTING.md: "mask secrets/PII in logs"; AGENTS.md invariant:
"A trace never logs access tokens, API keys, raw cookies or unnecessary
sensitive personal data."

The defence is layered, and the first layer is not here: the API key is typed as
`pydantic.SecretStr` all the way from `Settings`, so printing it, formatting it
into a message or dumping the whole settings object yields `**********` rather
than the key. Only `get_secret_value()` reveals it, and exactly one call site
does that — where the HTTP header is built.

`redact` is the second layer: a deliberate scrub for text that arrived from
somewhere else (a provider's error body, say) and is about to be put into a log
line or an exception message.
"""

from __future__ import annotations

import re

from pydantic import SecretStr

PLACEHOLDER = "***redacted***"

_SECRET_PATTERNS = (
    # An authorization header echoed back in an error body. The optional scheme must be consumed
    # as part of the match, or `Authorization: Bearer <token>` would redact only down to "Bearer"
    # and leave the token itself in the string.
    re.compile(r"(?i)\bauthorization\s*[:=]\s*(?:bearer\s+)?\S+"),
    # A bare `Bearer <token>` with no header name in front of it.
    re.compile(r"(?i)\bbearer\s+\S+"),
    # Common provider key shapes (OpenAI-style `sk-...`, and similar long opaque tokens).
    re.compile(r"\bsk-[A-Za-z0-9_\-]{8,}"),
)


def redact(text: str, *known_secrets: str | SecretStr | None) -> str:
    """Scrubs known secret shapes, plus any literal secret the caller can name."""
    scrubbed = text
    for secret in known_secrets:
        literal = secret.get_secret_value() if isinstance(secret, SecretStr) else secret
        if literal:
            scrubbed = scrubbed.replace(literal, PLACEHOLDER)
    for pattern in _SECRET_PATTERNS:
        scrubbed = pattern.sub(PLACEHOLDER, scrubbed)
    return scrubbed
