#!/usr/bin/env bash
#
# One command to reproduce the load test (docs/TASKS.md T34 acceptance: "one-command reproducible
# load test").
#
# Seeds the fixture into a database of its own, starts the API with a throwaway test-issuer key,
# mints a token, runs k6, and writes the raw JSON summary into benchmarks/results/.
#
#   make bench                    # full run
#   BENCH_LABEL=after make bench  # label the artefacts, e.g. for a before/after pair
#
# It needs PostgreSQL running (the usual POSTGRES_* from .env) and k6 installed. It does not need
# Redis, Docker, or the worker: every endpoint under test is synchronous and none of them calls a
# model — see the comment at the top of benchmarks/k6/api-load.js for why that separation is
# deliberate rather than a shortcut.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

LABEL="${BENCH_LABEL:-baseline}"
API_PORT="${BENCH_API_PORT:-8080}"
API_BASE="http://127.0.0.1:${API_PORT}"
BENCH_DB="${BENCH_POSTGRES_DB:-opspilot_bench}"
RESULTS_DIR="$REPO_ROOT/benchmarks/results"
STAMP="$(date -u +%Y-%m-%dT%H-%M-%SZ)"

# Org A from benchmarks/fixture.sql — the 10,000-ticket tenant.
ORG_ID="00000000-0000-4000-8000-00000000a001"

log() { printf '\033[36m[bench]\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[31m[bench] ✗ %s\033[0m\n' "$*" >&2; exit 1; }

API_PID=""
cleanup() {
  local status=$?
  if [ -n "$API_PID" ]; then
    log "stopping the API"
    kill "$API_PID" 2>/dev/null || true
  fi
  # By port as well: mvnw forks a JVM that does not die with the wrapper (the same lesson as
  # scripts/e2e-stack.sh).
  local holders
  holders="$(lsof -ti tcp:"$API_PORT" -sTCP:LISTEN 2>/dev/null || true)"
  if [ -n "$holders" ]; then
    # shellcheck disable=SC2086
    kill -9 $holders 2>/dev/null || true
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

command -v k6 >/dev/null 2>&1 || fail "k6 is not installed. brew install k6"
command -v psql >/dev/null 2>&1 || fail "psql is required to load the fixture."
[ -f .env ] || fail ".env not found; copy .env.example."

set -a
# shellcheck disable=SC1091
. ./.env
set +a

if lsof -ti tcp:"$API_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  fail "something is already listening on :$API_PORT. Stop it, or set BENCH_API_PORT."
fi

mkdir -p "$RESULTS_DIR"

# --- fixture ------------------------------------------------------------------------------------

log "loading the fixture into '$BENCH_DB' (10,000 + 1,000 tickets)"
PGPASSWORD="${POSTGRES_PASSWORD:-}" psql \
  -h "${POSTGRES_HOST:-127.0.0.1}" -p "${POSTGRES_PORT:-5432}" -U "${POSTGRES_USER:-opspilot}" \
  -d "$BENCH_DB" -v ON_ERROR_STOP=1 -q -f benchmarks/fixture.sql \
  || fail "fixture failed. Does '$BENCH_DB' exist and is it migrated? See benchmarks/README.md."

# --- API ------------------------------------------------------------------------------------------

log "generating a throwaway test-issuer key"
# base64 PKCS#8 from the PEM, not from -outform DER: on OpenSSL 3.6 the DER output for RSA is
# PKCS#1 and Java rejects it. Same finding as scripts/e2e-stack.sh.
TEST_KEY="$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null \
  | sed -e '/-----/d' | tr -d '\n')"

log "starting the API on :$API_PORT against '$BENCH_DB'"
(
  cd services/api
  POSTGRES_DB="$BENCH_DB" \
  API_PORT="$API_PORT" \
  API_ALLOW_TEST_ISSUER=true \
  API_TEST_ISSUER_PRIVATE_KEY="$TEST_KEY" \
  OUTBOX_PUBLISHER_ENABLED=false \
    ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local
) > "$RESULTS_DIR/${LABEL}-api.log" 2>&1 &
API_PID=$!

log "waiting for readiness"
for _ in $(seq 1 120); do
  if curl -fsS "$API_BASE/actuator/health/readiness" >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -fsS "$API_BASE/actuator/health/readiness" >/dev/null 2>&1 \
  || { tail -30 "$RESULTS_DIR/${LABEL}-api.log" >&2; fail "the API never became ready"; }

# --- token ----------------------------------------------------------------------------------------

log "minting a token for the fixture's owner"
TOKEN="$(
  cd tests/e2e && TEST_KEY="$TEST_KEY" node --input-type=module -e "
    import { createPrivateKey } from 'node:crypto';
    import { SignJWT } from 'jose';
    const key = createPrivateKey({
      key: Buffer.from(process.env.TEST_KEY, 'base64'), format: 'der', type: 'pkcs8',
    });
    process.stdout.write(await new SignJWT({
      'https://opspilot.local/email': 'load-fixture@bench.invalid',
      'https://opspilot.local/name': 'Load Fixture (Benchmark)',
    })
      .setProtectedHeader({ alg: 'RS256' })
      .setIssuer('https://test-issuer.opspilot.local/')
      .setSubject('bench|load-fixture')
      .setAudience('${API_OIDC_AUDIENCE:-https://api.opspilot.local}')
      .setIssuedAt().setExpirationTime('1h').sign(key));
  " 2>/dev/null
)"
[ -n "$TOKEN" ] || fail "could not mint a token (is tests/e2e installed? run pnpm install there)"

# --- a QUEUED run for the status endpoint ----------------------------------------------------------

# No worker is running, so this run stays QUEUED forever — which is exactly what the run-status
# scenario wants to read. It measures the API's read path with no model anywhere near it.
TICKET_ID="$(curl -fsS -H "Authorization: Bearer $TOKEN" \
  "$API_BASE/api/v1/organizations/$ORG_ID/tickets?limit=1" \
  | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>process.stdout.write(JSON.parse(s).items[0].id))")"

RUN_ID="$(curl -fsS -X POST \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"workflow":"SUPPORT_TRIAGE_V1"}' \
  "$API_BASE/api/v1/organizations/$ORG_ID/tickets/$TICKET_ID/runs" \
  | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>process.stdout.write(JSON.parse(s).runId))")"

log "run under test (stays QUEUED, no worker): $RUN_ID"

# --- k6 --------------------------------------------------------------------------------------------

SUMMARY="$RESULTS_DIR/${LABEL}-${STAMP}.json"
log "running k6 — summary will be $SUMMARY"

BASE_URL="$API_BASE" TOKEN="$TOKEN" ORG_ID="$ORG_ID" RUN_ID="$RUN_ID" \
  k6 run --summary-export "$SUMMARY" benchmarks/k6/api-load.js \
  | tee "$RESULTS_DIR/${LABEL}-${STAMP}.txt"

log "done. Raw summary: $SUMMARY"
