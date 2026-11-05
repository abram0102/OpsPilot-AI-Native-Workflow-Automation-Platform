SHELL := /bin/bash
.DEFAULT_GOAL := help

# -----------------------------------------------------------------------------
# OpsPilot Makefile
#
# As of ticket T00, only `help` does real work. Every other target below is a
# deliberate, clearly-labeled stub that FAILS (non-zero exit, explicit message)
# until the ticket that implements it lands — per AGENTS.md: "Configure
# scripts to fail with clear messages for not-yet-created services rather
# than silently passing." Do not let CI or a developer mistake a stub for a
# passing check.
# -----------------------------------------------------------------------------

.PHONY: help infra-up infra-down infra-logs infra-reset \
	api-dev agent-dev web-dev \
	lint test-unit test-integration test-contract test-e2e test-security \
	eval-mock eval-real check seed-demo

help: ## Show this help
	@echo "OpsPilot — available targets:"
	@echo ""
	@grep -E '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-16s %s\n", $$1, $$2}'
	@echo ""
	@echo "Most targets are stubs until their ticket in docs/TASKS.md lands (see docs/PROGRESS.md)."

infra-up: ## Start local Postgres+pgvector and Redis (Docker) — T01
	@printf '\033[31m✗ %s is not implemented yet\033[0m (ticket T01 in docs/TASKS.md). See docs/PROGRESS.md for status.\n' "$@" >&2
	@exit 1

infra-down: ## Stop local infra — T01
	@printf '\033[31m✗ %s is not implemented yet\033[0m (ticket T01 in docs/TASKS.md). See docs/PROGRESS.md for status.\n' "$@" >&2
	@exit 1

infra-logs: ## Tail local infra logs — T01
	@printf '\033[31m✗ %s is not implemented yet\033[0m (ticket T01 in docs/TASKS.md). See docs/PROGRESS.md for status.\n' "$@" >&2
	@exit 1

infra-reset: ## Destroy local infra volumes, interactive confirm required — T01
	@printf '\033[31m✗ %s is not implemented yet\033[0m (ticket T01 in docs/TASKS.md). See docs/PROGRESS.md for status.\n' "$@" >&2
	@exit 1

api-dev: ## Run the Spring Boot API in dev mode — T02
	@printf '\033[31m✗ %s is not implemented yet\033[0m (ticket T02 in docs/TASKS.md). See docs/PROGRESS.md for status.\n' "$@" >&2
	@exit 1

agent-dev: ## Run the Python AI worker in dev mode — T03
	@printf '\033[31m✗ %s is not implemented yet\033[0m (ticket T03 in docs/TASKS.md). See docs/PROGRESS.md for status.\n' "$@" >&2
	@exit 1

web-dev: ## Run the Next.js frontend in dev mode — T04
	@printf '\033[31m✗ %s is not implemented yet\033[0m (ticket T04 in docs/TASKS.md). See docs/PROGRESS.md for status.\n' "$@" >&2
	@exit 1

lint: ## Run all linters across web/api/agent — T02-T04
	@printf '\033[31m✗ %s is not implemented yet\033[0m (tickets T02-T04 in docs/TASKS.md). See docs/PROGRESS.md for status.\n' "$@" >&2
	@exit 1

test-unit: ## Run unit tests across services — T02-T04
	@printf '\033[31m✗ %s is not implemented yet\033[0m (tickets T02-T04 in docs/TASKS.md). See docs/PROGRESS.md for status.\n' "$@" >&2
	@exit 1

test-integration: ## Run Testcontainers integration tests — T06+
	@printf '\033[31m✗ %s is not implemented yet\033[0m (ticket T06 in docs/TASKS.md). See docs/PROGRESS.md for status.\n' "$@" >&2
	@exit 1

test-contract: ## Verify generated OpenAPI/TS client has no drift — T11
	@printf '\033[31m✗ %s is not implemented yet\033[0m (ticket T11 in docs/TASKS.md). See docs/PROGRESS.md for status.\n' "$@" >&2
	@exit 1

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

seed-demo: ## Seed fictional local/test tenants, users and tickets — T06+
	@printf '\033[31m✗ %s is not implemented yet\033[0m (ticket T06 in docs/TASKS.md). See docs/PROGRESS.md for status.\n' "$@" >&2
	@exit 1

check: ## Run static + unit + integration + contract + mock-eval checks — T29
	@printf '\033[31m✗ %s is not implemented yet\033[0m (ticket T29 in docs/TASKS.md). See docs/PROGRESS.md for status.\n' "$@" >&2
	@exit 1
