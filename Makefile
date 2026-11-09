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
	lint test-unit test-integration test-contract test-e2e test-security \
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

api-dev: ## Run the Spring Boot API in dev mode (needs `make infra-up` first) — T02
	cd services/api && ./mvnw spring-boot:run

agent-dev: ## Run the Python AI worker in dev mode — T03
	cd services/agent && uv run uvicorn opspilot_agent.main:app --reload --port $${AGENT_HEALTH_PORT:-8000}

web-dev: ## Run the Next.js frontend in dev mode — T04
	cd apps/web && $(NVM_USE) pnpm dev

lint: ## Run all linters across web/api/agent — T05
	cd apps/web && $(NVM_USE) pnpm lint
	cd services/agent && uv run ruff check .
	cd services/api && ./mvnw -q spotless:check

# Surefire needs `!` on *each* comma-separated name to exclude all of them
# (a bare name after a comma is a separate positive-include filter, not a
# continuation of the exclusion) — so these are two distinct strings, not
# one list reused with/without a prefix.
API_DOCKER_TESTS_EXCLUDE := !ApiApplicationTests,!FlywayMigrationIntegrationTest,!MembershipRepositoryIntegrationTest,!TicketRepositoryIntegrationTest,!SecurityConfigIntegrationTest,!JwtAuthenticationIntegrationTest,!OrganizationAccessHttpIntegrationTest,!MeControllerIntegrationTest,!OrganizationHttpIntegrationTest,!InvitationHttpIntegrationTest,!TicketHttpIntegrationTest,!TicketMessageHttpIntegrationTest,!OpenApiSpecDriftTest,!OpenApiSpecGenerator,!KnowledgeHttpIntegrationTest,!AgentPersistenceIntegrationTest
API_DOCKER_TESTS_INCLUDE := ApiApplicationTests,FlywayMigrationIntegrationTest,MembershipRepositoryIntegrationTest,TicketRepositoryIntegrationTest,SecurityConfigIntegrationTest,JwtAuthenticationIntegrationTest,OrganizationAccessHttpIntegrationTest,MeControllerIntegrationTest,OrganizationHttpIntegrationTest,InvitationHttpIntegrationTest,TicketHttpIntegrationTest,TicketMessageHttpIntegrationTest,OpenApiSpecDriftTest,KnowledgeHttpIntegrationTest,AgentPersistenceIntegrationTest

test-unit: ## Run fast, Docker-independent unit tests across services — T05
	cd apps/web && $(NVM_USE) pnpm test
	cd services/agent && uv run pytest -m 'not db'
	cd services/api && ./mvnw -q test -Dtest='$(API_DOCKER_TESTS_EXCLUDE)'

test-integration: _ensure-docker ## Run Testcontainers integration tests (needs Docker) — T05+
	cd services/api && ./mvnw -q test -Dtest=$(API_DOCKER_TESTS_INCLUDE)
	@echo "services/agent database-backed tests need POSTGRES_TEST_DSN pointing at a"
	@echo "PostgreSQL with pgvector (see docs/PROGRESS.md T16); skipped when it is unset."
	cd services/agent && uv run pytest -m db

generate-contracts: _ensure-docker ## Regenerate packages/contracts/{openapi.json,src/schema.d.ts} from the real endpoints — T11
	cd services/api && ./mvnw -q test -Dtest=OpenApiSpecGenerator
	cd packages/contracts && npx --yes openapi-typescript@7.13.0 openapi.json -o src/schema.d.ts
	@echo "Review the diff under packages/contracts/ and commit it."

test-contract: _ensure-docker ## Verify generated OpenAPI/TS client has no drift — T11
	cd services/api && ./mvnw -q test -Dtest=OpenApiSpecDriftTest
	cd packages/contracts && bash scripts/check-schema-drift.sh

test-e2e: ## Run Playwright E2E against the local stack — T09+
	@printf '\033[31m✗ %s is not implemented yet\033[0m (ticket T09 in docs/TASKS.md). See docs/PROGRESS.md for status.\n' "$@" >&2
	@exit 1

test-security: ## Run the security/tenant-isolation regression suite — T28
	@printf '\033[31m✗ %s is not implemented yet\033[0m (ticket T28 in docs/TASKS.md). See docs/PROGRESS.md for status.\n' "$@" >&2
	@exit 1

eval-mock: ## Run deterministic mock AI evaluation — T30
	@printf '\033[31m✗ %s is not implemented yet\033[0m (ticket T30 in docs/TASKS.md). See docs/PROGRESS.md for status.\n' "$@" >&2
	@exit 1

eval-real: ## Run real-provider AI evaluation, requires H2 gate + budget — T20
	@printf '\033[31m✗ %s is not implemented yet\033[0m (ticket T20 in docs/TASKS.md). See docs/PROGRESS.md for status.\n' "$@" >&2
	@exit 1

seed-demo: ## Seed fictional local/test tenants, users and tickets (needs infra-up first) — T06
	cd services/api && ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local,seed

check: ## Run static + unit + integration + contract + mock-eval checks — T29
	@printf '\033[31m✗ %s is not implemented yet\033[0m (ticket T29 in docs/TASKS.md). See docs/PROGRESS.md for status.\n' "$@" >&2
	@exit 1
