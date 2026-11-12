"""Optional error reporting (docs/TASKS.md T30: "Sentry error integration as optional env").

**Optional means optional.** `sentry-sdk` is deliberately not a dependency of this service. A
project that ships an error-reporting client it has never configured gains a package, an outbound
network client and a supply-chain entry, in exchange for nothing. So the import is lazy, and the
whole thing is inert unless `SENTRY_DSN` is set.

What it is *not* allowed to do is fail quietly. If an operator sets a DSN and the SDK is not
installed, that is a misconfiguration with real consequences — they believe errors are being
reported and they are not — so it raises. The two failure modes are "told you nothing was
configured" and "told you it could not configure", never "pretended".
"""

from __future__ import annotations

import logging

from opspilot_agent.config import Settings

log = logging.getLogger(__name__)


class SentryNotInstalledError(RuntimeError):
    """A DSN is configured but `sentry-sdk` is not importable."""


def configure_error_reporting(settings: Settings) -> bool:
    """Initialises Sentry if a DSN is configured. Returns whether it did.

    Returns a bool rather than nothing so a caller — and a test — can tell the difference between
    "configured" and "deliberately not configured" without inspecting global SDK state.
    """
    dsn = settings.sentry_dsn
    if not dsn:
        log.debug("SENTRY_DSN is not set; error reporting is off.")
        return False

    try:
        import sentry_sdk
    except ModuleNotFoundError as error:  # pragma: no cover - exercised via monkeypatch
        raise SentryNotInstalledError(
            "SENTRY_DSN is set but sentry-sdk is not installed, so no errors would be reported "
            "while appearing to be. Install it (`uv add sentry-sdk`) or unset SENTRY_DSN."
        ) from error

    sentry_sdk.init(
        dsn=dsn,
        # AGENTS.md: "A trace never logs access tokens, API keys, raw cookies or unnecessary
        # sensitive personal data." send_default_pii would attach request headers, cookies and
        # user identifiers to every event — exactly the thing that invariant forbids, and the
        # single most consequential flag in this call.
        send_default_pii=False,
        # The ticket text and document excerpts this worker handles are customer content. A
        # traceback's local variables routinely contain them, and an error report is not a place
        # for a tenant's support ticket to end up.
        include_local_variables=False,
        environment=settings.ai_mode.value,
        # Sampled, not exhaustive: a worker in a retry storm would otherwise send thousands of
        # identical events, which costs money and buries the first one.
        traces_sample_rate=0.0,
    )
    log.info("Error reporting is enabled.")
    return True
