SHELL := /bin/bash
.DEFAULT_GOAL := help

# -----------------------------------------------------------------------------
# OpsPilot Makefile
#
# Targets are wired up as the ticket that needs them lands (see
# docs/PROGRESS.md for current status). Anything not yet implemented is a
# deliberate, clearly-labeled stub that FAILS (non-zero exit, explicit
# message) rather than silently no-op'ing or faking success — per AGENTS.md:
# "Configure scripts to fail with clear messages for not-yet-created services
# rather than silently passing." Do not let CI or a developer mistake a stub
# for a passing check.
# -----------------------------------------------------------------------------

.PHONY: help infra-up infra-down infra-logs infra-reset \
	api-dev agent-dev web-dev \
	lint test-unit test-integration test-contract test-e2e \
	test-security test-security-external audit-deps \
	iac-validate iac-plan \
	infra-test-up infra-test-down \
	eval-mock eval-real check seed-demo generate-contracts

# Best-effort: switch to the Node version pinned in apps/web/.nvmrc if nvm is
# installed, so `pnpm test` doesn't hit the undici/Node ABI mismatch recorded
# in docs/DECISIONS.md ADR-010. Silently does nothing if nvm isn't installed
# (e.g. CI, which pins Node via actions/setup-node instead) — never fails the
# command that follows it.
NVM_USE := if [ -s "$$HOME/.nvm/nvm.sh" ]; then . "$$HOME/.nvm/nvm.sh" && nvm use >/dev/null; fi;

help: ## Show this help
	@echo "OpsPilot — available targets:"
	@echo ""
	@grep -E '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-16s %s\n", $$1, $$2}'
	@echo ""
	@echo "Most targets are stubs until their ticket in docs/TASKS.md lands (see docs/PROGRESS.md)."

COMPOSE := docker compose -f infra/docker-compose.yml --env-file .env

.PHONY: _ensure-env _ensure-docker

_ensure-env:
	@if [ ! -f .env ]; then \
		echo "No .env found — creating one from .env.example (local-only placeholders)."; \
		cp .env.example .env; \
	fi

_ensure-docker:
	@command -v docker >/dev/null 2>&1 || { \
		echo "✗ docker is not installed or not on PATH. See docs/MANUAL_SETUP.md Gate H0 (install Docker Desktop)." >&2; \
		exit 1; \
	}

infra-up: _ensure-docker _ensure-env ## Start local Postgres+pgvector and Redis (Docker) — T01
	$(COMPOSE) up -d
	@echo "Waiting for health checks..."
	@bash scripts/check-infra.sh

infra-down: _ensure-docker _ensure-env ## Stop local infra, volumes are kept — T01
	$(COMPOSE) down

COMPOSE_TEST := docker compose -f infra/docker-compose.test.yml

infra-test-up: _ensure-docker ## Start throwaway Postgres+Redis for tests (tmpfs, own ports) — T29
	$(COMPOSE_TEST) up -d --wait

infra-test-down: _ensure-docker ## Stop and remove the throwaway test services — T29
	$(COMPOSE_TEST) down -v

infra-logs: _ensure-docker _ensure-env ## Tail local infra logs — T01
	$(COMPOSE) logs -f

infra-reset: _ensure-docker _ensure-env ## Destroy local infra containers AND volumes — T01 (interactive confirm)
	@echo "⚠️  This will PERMANENTLY DELETE local Postgres and Redis data (opspilot-postgres-data, opspilot-redis-data)."
	@read -p "Type 'reset' to confirm, anything else aborts: " confirm; \
	if [ "$$confirm" != "reset" ]; then \
		echo "Aborted — no changes made."; \
		exit 1; \
	fi
	$(COMPOSE) down -v

api-dev: _ensure-env ## Run the Spring Boot API in dev mode (needs `make infra-up` first) — T02
	# `.env` really is loaded here now (T24). application-local.yml has claimed it since T02, but
	# nothing was exporting it: POSTGRES_PASSWORD has no default, and OUTBOX_PUBLISHER_ENABLED
	# would never have reached the process either. `set -a` exports every assignment in the file.
	set -a && . ./.env && set +a && cd services/api && ./mvnw spring-boot:run

agent-dev: ## Run the Python AI worker's health/queue API in dev mode — T03
	cd services/agent && uv run uvicorn opspilot_agent.main:app --reload --port $${AGENT_HEALTH_PORT:-8000}

worker-dev: _ensure-env ## Run the Redis Streams consumer (needs `make infra-up` first) — T25
	# The process that actually does the work; `agent-dev` only serves health and queue depth.
	set -a && . ./.env && set +a && cd services/agent && uv run python -m opspilot_agent.streams

worker-health: _ensure-env ## Print queue depth, backlog and dead-letter count as JSON — T25
	set -a && . ./.env && set +a && cd services/agent && uv run python -m opspilot_agent.streams --health

web-dev: ## Run the Next.js frontend in dev mode — T04
	cd apps/web && $(NVM_USE) pnpm dev

lint: ## Run all linters across web/api/agent/e2e — T05, T29
	cd apps/web && $(NVM_USE) pnpm lint
	cd services/agent && uv run ruff check .
	cd services/api && ./mvnw -q spotless:check
	# The E2E package has no runtime of its own to lint, but it does have types — and a selector
	# typo there only shows up minutes into a stack run, which is the worst place to find one.
	cd tests/e2e && $(NVM_USE) pnpm typecheck

# Surefire needs `!` on *each* comma-separated name to exclude all of them
# (a bare name after a comma is a separate positive-include filter, not a
# continuation of the exclusion) — so these are two distinct strings, not
# one list reused with/without a prefix.
API_DOCKER_TESTS_EXCLUDE := !ApiApplicationTests,!FlywayMigrationIntegrationTest,!MembershipRepositoryIntegrationTest,!TicketRepositoryIntegrationTest,!SecurityConfigIntegrationTest,!JwtAuthenticationIntegrationTest,!OrganizationAccessHttpIntegrationTest,!MeControllerIntegrationTest,!OrganizationHttpIntegrationTest,!InvitationHttpIntegrationTest,!TicketHttpIntegrationTest,!TicketMessageHttpIntegrationTest,!OpenApiSpecDriftTest,!OpenApiSpecGenerator,!KnowledgeHttpIntegrationTest,!AgentPersistenceIntegrationTest,!AgentRunHttpIntegrationTest,!InternalAgentActionHttpIntegrationTest,!ApprovalHttpIntegrationTest,!OutboxPublisherIntegrationTest,!OutboxReadinessIntegrationTest,!AgentActionIdempotencyIntegrationTest,!SeedProfileIntegrationTest
API_DOCKER_TESTS_INCLUDE := ApiApplicationTests,FlywayMigrationIntegrationTest,MembershipRepositoryIntegrationTest,TicketRepositoryIntegrationTest,SecurityConfigIntegrationTest,JwtAuthenticationIntegrationTest,OrganizationAccessHttpIntegrationTest,MeControllerIntegrationTest,OrganizationHttpIntegrationTest,InvitationHttpIntegrationTest,TicketHttpIntegrationTest,TicketMessageHttpIntegrationTest,OpenApiSpecDriftTest,KnowledgeHttpIntegrationTest,AgentPersistenceIntegrationTest,AgentRunHttpIntegrationTest,InternalAgentActionHttpIntegrationTest,ApprovalHttpIntegrationTest,OutboxPublisherIntegrationTest,OutboxReadinessIntegrationTest,AgentActionIdempotencyIntegrationTest,SeedProfileIntegrationTest

test-unit: ## Run fast, Docker-independent unit tests across services — T05
	cd apps/web && $(NVM_USE) pnpm test
	cd services/agent && uv run pytest -m 'not db and not redis'
	cd services/api && ./mvnw -q test -Dtest='$(API_DOCKER_TESTS_EXCLUDE)'

test-integration: _ensure-docker ## Run Testcontainers integration tests (needs Docker) — T05+
	cd services/api && ./mvnw -q test -Dtest=$(API_DOCKER_TESTS_INCLUDE)
	@echo "services/agent database-backed tests need POSTGRES_TEST_DSN pointing at a"
	@echo "PostgreSQL with pgvector (see docs/PROGRESS.md T16); skipped when it is unset."
	cd services/agent && uv run pytest -m db
	@echo "T25's consumer tests need REDIS_TEST_URL pointing at a real Redis; skipped when unset."
	cd services/agent && uv run pytest -m redis

test-integration-external: ## Same Java tests, against servers you are already running (no Docker) — T26
	# For a machine with no Docker (Gate H0). Point POSTGRES_* at a PostgreSQL with pgvector and
	# REDIS_* at a Redis, and the Testcontainers configurations stand aside. This is how T26's
	# concurrency tests were verified before CI — which is how the retry bug in the tool-execution
	# transition table was found at all.
	cd services/api && ./mvnw -q test \
		-Dopspilot.test.external-postgres=true -Dopspilot.test.external-redis=true

generate-contracts: _ensure-docker ## Regenerate packages/contracts/{openapi.json,src/schema.d.ts} from the real endpoints — T11
	cd services/api && ./mvnw -q test -Dtest=OpenApiSpecGenerator
	cd packages/contracts && npx --yes openapi-typescript@7.13.0 openapi.json -o src/schema.d.ts
	@echo "Review the diff under packages/contracts/ and commit it."

test-contract: _ensure-docker ## Verify generated OpenAPI/TS client has no drift — T11
	cd services/api && ./mvnw -q test -Dtest=OpenApiSpecDriftTest
	cd packages/contracts && bash scripts/check-schema-drift.sh

test-e2e: _ensure-env ## Run Playwright E2E against a freshly started stack — T29
	# Starts the API, worker and web app with a per-run test-issuer key, seeds the two demo
	# tenants, runs Playwright, and tears everything down. Needs PostgreSQL and Redis already
	# running (`make infra-up`, or your own servers). Uses its own database — see the script.
	bash scripts/e2e-stack.sh

# The suite is selected by tag/marker, not by a list of file names: a list is a thing that falls
# out of date silently, and a security suite that quietly stopped covering an endpoint is worse
# than no suite. Java classes carry @Tag("security"); Python modules carry
# `pytestmark = pytest.mark.security`; the web side has no tag mechanism, so its two
# boundary tests are named explicitly.
test-security: _ensure-docker ## Run the security/tenant-isolation regression suite — T28
	cd services/api && ./mvnw -q test -Dgroups=security
	cd services/agent && uv run pytest -m security --strict-markers
	cd apps/web && $(NVM_USE) pnpm vitest run \
		src/app/api/run-status/route.test.ts \
		src/lib/api/knowledge-rules.test.ts

test-security-external: ## Same suite, against servers you are already running (no Docker) — T28
	# Gate H0 escape hatch, same as test-integration-external. Point POSTGRES_* at a PostgreSQL
	# with pgvector and REDIS_* at a Redis. Use a database of its own: these tests migrate and
	# truncate, so pointing them at the database `make api-dev` is using will disturb it.
	cd services/api && ./mvnw -q test -Dgroups=security \
		-Dopspilot.test.external-postgres=true -Dopspilot.test.external-redis=true
	cd services/agent && uv run pytest -m security --strict-markers
	cd apps/web && $(NVM_USE) pnpm vitest run \
		src/app/api/run-status/route.test.ts \
		src/lib/api/knowledge-rules.test.ts

# --- infrastructure (T31) -----------------------------------------------------------------------
#
# Everything here is static. Nothing in this file can reach an AWS account: `tofu init
# -backend=false` fetches the provider from the public registry and configures no state backend,
# and neither `validate` nor `tflint` makes an API call. `plan` and `apply` are deliberately NOT
# make targets — see infra/aws/README.md and Gate H3 in docs/MANUAL_SETUP.md.

IAC_DIR := infra/aws

iac-validate: ## Format-check, validate and lint the AWS IaC. Makes no AWS calls — T31
	@command -v tofu >/dev/null 2>&1 || { \
		echo "✗ opentofu is not installed. brew install opentofu (or use terraform: the HCL is the same)." >&2; \
		exit 1; \
	}
	cd $(IAC_DIR) && tofu fmt -recursive -check
	cd $(IAC_DIR) && tofu init -backend=false -input=false >/dev/null
	cd $(IAC_DIR) && tofu validate
	@command -v tflint >/dev/null 2>&1 || { \
		echo "note: tflint is not installed, skipping the provider-schema lint." >&2; \
		exit 0; \
	}
	# Per directory, explicitly. `tflint --recursive` does not apply this config to the module
	# directories — it looks for a .tflint.hcl in each one — so a recursive run reported three
	# issues where an explicit sweep found forty-seven.
	@cd $(IAC_DIR) && for dir in . modules/*/; do \
		(cd "$$dir" && tflint --config "$(CURDIR)/$(IAC_DIR)/.tflint.hcl" --format compact) || exit 1; \
	done
	@printf '\033[32m✓ IaC format, validate and lint passed (no AWS calls made)\033[0m\n'

iac-plan: ## Refuses. A plan needs real AWS credentials and is a Gate H3 step — T31
	@printf '\033[33m! %s is deliberately not a make target.\033[0m\n' "$@" >&2
	@echo "A plan requires AWS credentials and a state backend, which is Gate H3 in docs/MANUAL_SETUP.md." >&2
	@echo "Once that gate is open, run it yourself and read the output:" >&2
	@echo "" >&2
	@echo "  cd $(IAC_DIR) && tofu init && tofu plan -var-file=environments/staging.tfvars" >&2
	@echo "" >&2
	@echo "Nothing in this repository will run plan or apply for you." >&2
	@exit 1

# docs/SECURITY_AND_TESTING.md §8.5: "dependency advisory check with actionable report".
# OSV-Scanner reads this repo's three lockfiles directly (pom.xml, uv.lock, pnpm-lock.yaml) and
# needs no API key or registry account, which is why it is here rather than three ecosystem-native
# audit commands with three different output formats and three different failure conventions.
# Dependabot (.github/dependabot.yml) opens the update PRs; this is the gate that fails a build.
audit-deps: ## Scan the committed lockfiles for known vulnerabilities (OSV) — T28
	@command -v osv-scanner >/dev/null 2>&1 || { \
		echo "✗ osv-scanner is not installed. brew install osv-scanner, or see https://google.github.io/osv-scanner/installation/" >&2; \
		echo "  CI runs this via google/osv-scanner-action, so a missing local binary blocks nothing but this target." >&2; \
		exit 1; \
	}
	osv-scanner scan source --recursive .

eval-mock: ## Run the offline evaluations: smoke (T20) + scored triage over 60 cases (T30)
	cd services/agent && uv run python -m opspilot_agent.evals

eval-real: ## Run the real-provider evaluations — BILLABLE, requires Gate H2 — T20/T30
	@printf '\033[33m! %s calls a paid LLM provider.\033[0m It refuses unless Gate H2 is set up (AI_MODE=real, ALLOW_REAL_LLM_CALLS, LLM_API_KEY, model ids).\n' "$@" >&2
	cd services/agent && uv run python -m opspilot_agent.evals --real

seed-demo: ## Seed fictional local/test tenants, users and tickets (needs infra-up first) — T06
	cd services/api && ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local,seed

# docs/SECURITY_AND_TESTING.md §4: "static checks + unit + integration + contract + offline eval".
# E2E is deliberately NOT here — it starts servers and takes minutes, and §4 says browser/service
# work "may be separate in CI". `make test-e2e` runs it.
check: _ensure-docker ## Run static + unit + integration + contract + mock-eval checks — T29
	$(MAKE) lint
	$(MAKE) test-unit
	$(MAKE) test-integration
	$(MAKE) test-contract
	$(MAKE) eval-mock
	python3 scripts/check-threat-model.py
	@printf '\033[32m✓ make check passed\033[0m\n'
