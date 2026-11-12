#!/usr/bin/env bash
#
# Brings up the whole stack with a signed offline test identity, runs Playwright against it, and
# tears it down again (docs/TASKS.md T29).
#
# The reason this is a script and not a Playwright `webServer` block: the API has to be started
# *with* the test issuer's private key, and the same key has to reach the test process. Playwright
# can start a server, but it cannot generate a key before the server it starts. So the key is
# generated here, once, and handed to both.
#
# Nothing it generates is written to the repository. The key and the session secret live in this
# process's environment and die with it.
#
# Postgres and Redis are assumed to be running already — `make infra-up` with Docker, or your own
# servers via POSTGRES_*/REDIS_* (Gate H0). This script does not start them, because the two cases
# want different things and guessing wrong would silently point the tests at a developer's own data.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

API_PORT="${E2E_API_PORT:-8080}"
WEB_PORT="${E2E_WEB_PORT:-3000}"
API_BASE="http://127.0.0.1:${API_PORT}"
WEB_BASE="http://127.0.0.1:${WEB_PORT}"
LOG_DIR="${E2E_LOG_DIR:-$REPO_ROOT/tests/e2e/test-results/stack-logs}"

mkdir -p "$LOG_DIR"

PIDS=()

log() { printf '\033[36m[e2e-stack]\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[31m[e2e-stack] ✗ %s\033[0m\n' "$*" >&2; exit 1; }

cleanup() {
  local status=$?
  log "stopping the stack"
  for pid in "${PIDS[@]:-}"; do
    [ -n "$pid" ] || continue
    kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
  done

  # Then by port, which is what actually works. `mvnw spring-boot:run` forks a JVM and `next start`
  # forks workers, and in a non-interactive shell the background job is not a process-group leader,
  # so `kill -- -$pid` does not reach the children. The first run after this guard existed caught
  # the consequence: a leftover API still on :8080, refusing the next run. Killing the listener is
  # safe precisely because require_free_port asserted the port was free before anything started —
  # whatever holds it now is ours.
  for port in "$API_PORT" "$WEB_PORT"; do
    local holders
    holders="$(lsof -ti tcp:"$port" -sTCP:LISTEN 2>/dev/null || true)"
    if [ -n "$holders" ]; then
      # shellcheck disable=SC2086
      kill $holders 2>/dev/null || true
      sleep 1
      holders="$(lsof -ti tcp:"$port" -sTCP:LISTEN 2>/dev/null || true)"
      # shellcheck disable=SC2086
      [ -n "$holders" ] && kill -9 $holders 2>/dev/null || true
    fi
  done

  wait 2>/dev/null || true
  exit "$status"
}
trap cleanup EXIT INT TERM

# --- preconditions ------------------------------------------------------------------------------

[ -f .env ] || fail ".env not found. Copy .env.example and fill it in (make _ensure-env does this)."
# .env is runtime config, not a tracked file, so there is nothing for shellcheck to follow.
# shellcheck disable=SC1091
set -a
# shellcheck disable=SC1091
. ./.env
set +a

command -v openssl >/dev/null 2>&1 || fail "openssl is required to generate the test identity key."

# This suite seeds tenants and creates tickets, so it gets a database of its own. Pointing it at
# the database `make api-dev` uses would mix test rows into a developer's own data and, worse,
# would make a failing test look like a data problem. Override with E2E_POSTGRES_DB; in CI the
# compose stack provides it.
export POSTGRES_DB="${E2E_POSTGRES_DB:-opspilot_e2e}"
log "using database '$POSTGRES_DB' on ${POSTGRES_HOST:-127.0.0.1}:${POSTGRES_PORT:-5432}"

# The Node version must come from apps/web/.nvmrc explicitly. A bare `nvm use` resolves .nvmrc
# relative to the *current* directory, and this script runs from the repository root, which has
# none — so it quietly failed and left whatever node the shell defaulted to. That was Node 20 here,
# and Next.js 16 on Node 20 does not fail loudly: `pnpm build` got all the way to prerendering and
# died on "/_global-error ... Cannot read properties of null (reading 'useContext')", which looks
# like an application bug and is not one (docs/DECISIONS.md ADR-010 records the same mismatch
# breaking `pnpm test`).
if [ -s "$HOME/.nvm/nvm.sh" ]; then
  # Optional, user-installed; nothing to follow.
  # shellcheck disable=SC1091
  . "$HOME/.nvm/nvm.sh"
  nvm use "$(cat apps/web/.nvmrc)" >/dev/null 2>&1 || log "nvm could not select Node $(cat apps/web/.nvmrc); using $(node -v 2>/dev/null || echo 'an unknown node')"
fi
log "node $(node -v), pnpm $(pnpm --version 2>/dev/null || echo '?')"

# --- the offline test identity ------------------------------------------------------------------

log "generating a throwaway test-issuer key pair and session secret"
# base64 PKCS#8 on one line — the format TestIssuerConfig parses.
#
# Taken from the PEM and stripped, NOT from `-outform DER`. On OpenSSL 3.6 the DER output for
# `-algorithm RSA` is PKCS#1 (it begins `30 82 .. 02 01 00 02 82`, the RSAPrivateKey sequence)
# while the PEM output is PKCS#8 (`BEGIN PRIVATE KEY`, with the rsaEncryption OID). Java's
# PKCS8EncodedKeySpec rejects the former with "algid parse error, not a sequence", which is how
# this was found: the API refused to start on the first run of the suite.
TEST_ISSUER_KEY="$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null | sed -e '/-----/d' | tr -d '\n')"
SESSION_SECRET="$(openssl rand -hex 32)"

export API_TEST_ISSUER_PRIVATE_KEY="$TEST_ISSUER_KEY"
export E2E_TEST_ISSUER_PRIVATE_KEY="$TEST_ISSUER_KEY"
export E2E_AUTH0_SECRET="$SESSION_SECRET"
export E2E_WEB_BASE_URL="$WEB_BASE"
export E2E_API_BASE_URL="$API_BASE"
export E2E_API_AUDIENCE="${API_OIDC_AUDIENCE:-https://api.opspilot.local}"

# --- no stale servers ----------------------------------------------------------------------------

# A readiness check that passes because something *else* is already listening is worse than one
# that times out: the suite runs green against a server from a previous run, built from different
# code. That happened here — a leftover `next start` answered /sign-in in 0s and a whole run tested
# the wrong build. So the ports are claimed up front, loudly.
require_free_port() {
  local name="$1" port="$2"
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    log "something is already listening on :$port —"
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >&2 || true
    fail "$name cannot start on :$port. Stop whatever holds it (a previous run, or \`make api-dev\`/\`make web-dev\`), or set E2E_API_PORT/E2E_WEB_PORT."
  fi
}

require_free_port "the API" "$API_PORT"
require_free_port "the web app" "$WEB_PORT"

# --- readiness ------------------------------------------------------------------------------------

# Polls a URL until it answers, rather than sleeping a fixed number of seconds. docs/TASKS.md T29
# asks for "nonflaky readiness checks", and a fixed sleep is the canonical flaky one: too short on a
# cold CI runner, wasted time everywhere else.
wait_for() {
  local name="$1" url="$2" timeout="${3:-120}" logfile="$4"
  local waited=0
  log "waiting for $name at $url (up to ${timeout}s)"
  until curl -fsS -o /dev/null "$url" 2>/dev/null; do
    if [ "$waited" -ge "$timeout" ]; then
      log "--- last 40 lines of $logfile ---"
      tail -40 "$logfile" >&2 || true
      fail "$name did not become ready within ${timeout}s"
    fi
    sleep 1
    waited=$((waited + 1))
  done
  log "$name is ready after ${waited}s"
}

# --- a clean database every run ------------------------------------------------------------------

# Without this the suite is not deterministic: tickets and published agent messages accumulate
# across runs, so "the ticket has one agent message" becomes "...has three", and a test that
# counts anything starts passing or failing based on how many times you have run it today. In CI
# the service container is fresh; locally the database persists, which is the case this handles.
#
# Guarded on the name. Dropping a schema is destructive, and the one thing that must never happen
# is this pointing at a development database — so it refuses unless the name says it is for tests.
reset_database() {
  case "$POSTGRES_DB" in
    *e2e* | *test*) ;;
    *)
      fail "refusing to reset '$POSTGRES_DB' — this script drops and recreates the schema, and that name does not look like a test database. Set E2E_POSTGRES_DB."
      ;;
  esac

  command -v psql >/dev/null 2>&1 || fail "psql is required to reset the test database."
  log "resetting the '$POSTGRES_DB' schema"
  PGPASSWORD="${POSTGRES_PASSWORD:-}" psql \
    -h "${POSTGRES_HOST:-127.0.0.1}" -p "${POSTGRES_PORT:-5432}" -U "${POSTGRES_USER:-opspilot}" \
    -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -q \
    -c "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; CREATE EXTENSION IF NOT EXISTS vector;" \
    || fail "could not reset '$POSTGRES_DB'. Does it exist, and can ${POSTGRES_USER:-opspilot} connect?"
}

reset_database

# --- API ------------------------------------------------------------------------------------------

log "starting services/api on :${API_PORT}"
(
  cd services/api
  API_PORT="$API_PORT" API_ALLOW_TEST_ISSUER=true \
    ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local
) > "$LOG_DIR/api.log" 2>&1 &
PIDS+=($!)

wait_for "the API" "$API_BASE/actuator/health/readiness" 180 "$LOG_DIR/api.log"

# --- seed data --------------------------------------------------------------------------------------

# The two tenants the isolation tests need. Idempotent, so re-running the suite is fine.
log "seeding the two demo tenants"
(cd services/api && ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local,seed) \
  > "$LOG_DIR/seed.log" 2>&1 || {
  tail -30 "$LOG_DIR/seed.log" >&2
  fail "seeding failed"
}

# --- worker -------------------------------------------------------------------------------------

log "starting the agent worker (AI_MODE=mock)"
(
  cd services/agent
  AI_MODE=mock uv run python -m opspilot_agent.streams
) > "$LOG_DIR/worker.log" 2>&1 &
PIDS+=($!)

# --- web ------------------------------------------------------------------------------------------

log "building and starting apps/web on :${WEB_PORT}"
(
  cd apps/web
  # Dummy Auth0 application values: the harness never performs an OIDC exchange, it mints the
  # session cookie directly. They exist only so isAuth0Configured() is true and the SDK's proxy
  # mounts and reads sessions. AUTH0_SECRET is the one that matters — it is what the cookie is
  # encrypted with, and the test process holds the same value.
  # .invalid is reserved by RFC 2606 and can never resolve, which is the point: if anything in
  # this suite ever tried a real OIDC exchange, it would fail loudly rather than quietly reaching
  # someone's tenant. gitleaks' generic-api-key rule matches the assignment because the variable
  # name contains AUTH0; allowed on this line only, rather than by adding a repository-wide
  # exception that would also hide a real one.
  export AUTH0_DOMAIN="e2e.opspilot.invalid" # gitleaks:allow
  export AUTH0_CLIENT_ID="e2e-client-id"
  export AUTH0_CLIENT_SECRET="e2e-client-secret-not-used-for-anything"
  export AUTH0_AUDIENCE="$E2E_API_AUDIENCE"
  export AUTH0_SECRET="$SESSION_SECRET"
  export APP_BASE_URL="$WEB_BASE"
  export NEXT_PUBLIC_API_BASE_URL="$API_BASE"
  export PORT="$WEB_PORT"
  # Overriding what `.env` exported. The repository's .env ships NODE_ENV=development (so does
  # .env.example), and this script sources it with `set -a`, so without this line `next build`
  # inherits "development" and builds a development bundle. It does not say so: the build runs all
  # the way to prerendering and fails on "/_global-error ... Cannot read properties of null
  # (reading 'useContext')", which reads as an application bug. `next build` only defaults
  # NODE_ENV to production when nothing has set it.
  export NODE_ENV=production
  pnpm build && pnpm start --port "$WEB_PORT"
) > "$LOG_DIR/web.log" 2>&1 &
PIDS+=($!)

wait_for "the web app" "$WEB_BASE/sign-in" 240 "$LOG_DIR/web.log"

# --- run ------------------------------------------------------------------------------------------

log "running Playwright"
(
  cd tests/e2e
  pnpm exec playwright test "$@"
)
