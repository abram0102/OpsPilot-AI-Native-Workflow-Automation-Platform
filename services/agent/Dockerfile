# syntax=docker/dockerfile:1
# Not yet build/run-verified in this environment (no Docker here — see
# docs/PROGRESS.md T03, same Gate H0 as T01/T02). Re-verify with
# `docker build .` once Docker is available.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/app/.venv \
    PATH="/app/.venv/bin:${PATH}"

# Pin uv's own version so the build is reproducible across machines/CI.
RUN pip install --no-cache-dir uv==0.13.7

RUN useradd --create-home --uid 1000 --shell /usr/sbin/nologin opspilot

WORKDIR /app
COPY pyproject.toml uv.lock ./
COPY src ./src
RUN chown -R opspilot:opspilot /app

USER opspilot

# --frozen: never silently re-resolve against a lockfile mismatch.
# --no-dev: pytest/ruff/httpx stay out of the runtime image.
RUN uv sync --frozen --no-dev

EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health/live', timeout=2).status == 200 else 1)"

CMD ["uvicorn", "opspilot_agent.main:app", "--host", "0.0.0.0", "--port", "8000"]
