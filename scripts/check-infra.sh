#!/usr/bin/env bash
# Polls local Postgres+pgvector and Redis (started via `make infra-up`) until
# healthy, then reports status. See docs/TASKS.md T01.
#
# Exits non-zero (with a clear message) if either service fails to become
# healthy within the timeout — never silently reports success.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if ! command -v docker >/dev/null 2>&1; then
  echo "✗ docker is not installed or not on PATH. See docs/MANUAL_SETUP.md Gate H0." >&2
  exit 1
fi

COMPOSE_FILE="infra/docker-compose.yml"
ENV_FILE=".env"

if [ ! -f "$ENV_FILE" ]; then
  echo "✗ Missing $ENV_FILE. Run: cp .env.example .env" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

POSTGRES_USER="${POSTGRES_USER:-opspilot}"
POSTGRES_DB="${POSTGRES_DB:-opspilot}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
REDIS_PORT="${REDIS_PORT:-6379}"

MAX_ATTEMPTS=30
SLEEP_SECONDS=2

compose() {
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
}

echo "Checking PostgreSQL (127.0.0.1:${POSTGRES_PORT})..."
attempt=0
until compose exec -T postgres pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
    echo "✗ PostgreSQL did not become ready after $((MAX_ATTEMPTS * SLEEP_SECONDS))s" >&2
    echo "  Run 'make infra-logs' to inspect." >&2
    exit 1
  fi
  sleep "$SLEEP_SECONDS"
done
echo "✓ PostgreSQL is accepting connections"

DB_NAME=$(compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT current_database();")
echo "  current_database() = ${DB_NAME// /}"

VECTOR_VERSION=$(compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
  "SELECT extversion FROM pg_extension WHERE extname = 'vector';" 2>/dev/null || true)
if [ -n "${VECTOR_VERSION// /}" ]; then
  echo "✓ pgvector extension enabled (version ${VECTOR_VERSION// /})"
else
  echo "… pgvector extension not created yet — expected until the Flyway V001 migration (ticket T06) runs CREATE EXTENSION vector. The pgvector/pgvector:pg16 image supports it."
fi

echo "Checking Redis (127.0.0.1:${REDIS_PORT})..."
attempt=0
until [ "$(compose exec -T redis redis-cli ping 2>/dev/null | tr -d '\r')" = "PONG" ]; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
    echo "✗ Redis did not respond to PING after $((MAX_ATTEMPTS * SLEEP_SECONDS))s" >&2
    echo "  Run 'make infra-logs' to inspect." >&2
    exit 1
  fi
  sleep "$SLEEP_SECONDS"
done
echo "✓ Redis responded PONG"

echo "All local infrastructure is healthy."
