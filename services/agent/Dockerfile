# syntax=docker/dockerfile:1
#
# Python agent worker (docs/TASKS.md T32). Rewritten from T03's single-stage version.
#
# Two things changed, and the second was a real bug waiting to happen:
#
#   1. Multi-stage. `uv` itself, the build cache and the lockfile resolution stay in the builder;
#      the runtime image carries an interpreter and a virtualenv and nothing else.
#   2. The CMD. T03's image started `uvicorn ... main:app` — the *health* API. That is not what
#      the ECS worker task runs: the consumer is `python -m opspilot_agent.streams`, and an image
#      whose default command serves health checks while consuming nothing would have looked
#      perfectly healthy in ECS forever. The health server is still available as an explicit
#      override; see `docker run ... opspilot-worker health`.

# --- build ---------------------------------------------------------------------------------------

FROM python:3.15-slim-bookworm AS build

# Pinned, so the build resolves the same way on every machine. Matches the version recorded in
# docs/DEPENDENCY_DECISIONS.md for T03.
COPY --from=ghcr.io/astral-sh/uv:0.13.7 /uv /usr/local/bin/uv

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/app/.venv

WORKDIR /app

# Dependencies as their own layer, resolved before the source is copied: a code change should not
# re-resolve or re-download anything.
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev --no-install-project

COPY src ./src

# Now the project itself. --frozen never silently re-resolves against a lockfile mismatch;
# --no-dev keeps pytest, ruff and httpx out of the runtime image.
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev

# --- runtime -------------------------------------------------------------------------------------

FROM python:3.15-slim-bookworm AS runtime

# uid/gid 10001, not 1000. The base images disagree about who owns 1000: Ubuntu Noble (the API's
# JRE base) ships a default `ubuntu` user there and node:26 ships `node` there, so `groupadd
# --gid 1000` exits 4, "GID already in use". Debian slim happens to leave it free, which is
# exactly the kind of inconsistency that makes one image fail and another pass for no visible
# reason — CI caught it on the API and the web image would have failed the same way one step
# later. A high, fixed id is free on all three and collides with no host user either.
RUN groupadd --gid 10001 opspilot \
    && useradd --uid 10001 --gid 10001 --create-home --shell /usr/sbin/nologin opspilot

WORKDIR /app

# The virtualenv and the source, owned by the runtime user. No uv, no build cache, no lockfile.
COPY --from=build --chown=10001:10001 /app/.venv /app/.venv
COPY --from=build --chown=10001:10001 /app/src /app/src

ENV PATH="/app/.venv/bin:${PATH}" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/src

USER 10001:10001

# The stream consumer. No port, no listener: nothing connects to the worker — it consumes a Redis
# stream and calls the internal API outbound (docs/SECURITY_AND_TESTING.md §1, worker boundary).
#
# There is deliberately no HEALTHCHECK. A container health check needs something to ask, and this
# process serves nothing; ECS treats a task whose process exits as failed, which is the correct
# signal for a consumer. Queue health is reported by `make worker-health` and the metrics in
# docs/OBSERVABILITY.md, not by this container.
ENTRYPOINT ["python", "-m"]
CMD ["opspilot_agent.streams"]
