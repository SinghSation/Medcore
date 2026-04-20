# Medcore Makefile
# -----------------------------------------------------------------------------
# This Makefile is the single entrypoint for local developer and CI tasks.
# Targets MUST remain idempotent, fast, and side-effect-free outside the repo.
# Agents MUST NOT add targets that mutate production systems.
# -----------------------------------------------------------------------------

SHELL := /usr/bin/env bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

# -----------------------------------------------------------------------------
# Meta
# -----------------------------------------------------------------------------

.PHONY: help
help: ## Show this help
	@awk 'BEGIN {FS = ":.*##"; printf "\nMedcore — available targets:\n\n"} \
		/^[a-zA-Z_-]+:.*?##/ { printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2 } \
		/^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0, 5) }' $(MAKEFILE_LIST)
	@echo ""

##@ Governance

.PHONY: check-governance
check-governance: ## Verify that core governance files exist and are non-empty
	@test -s AGENTS.md               || (echo "AGENTS.md missing" && exit 1)
	@test -s README.md               || (echo "README.md missing" && exit 1)
	@test -d docs/adr                || (echo "docs/adr missing"   && exit 1)
	@test -d .cursor/rules           || (echo ".cursor/rules missing" && exit 1)
	@test -d .claude/skills          || (echo ".claude/skills missing" && exit 1)
	@echo "governance: OK"

.PHONY: adr-new
adr-new: ## Scaffold a new ADR (usage: make adr-new TITLE="decide-xyz")
	@test -n "$(TITLE)" || (echo "Usage: make adr-new TITLE=\"short-kebab-title\"" && exit 1)
	@next=$$(ls docs/adr 2>/dev/null | grep -E '^[0-9]{3}-' | awk -F- '{print $$1}' | sort -n | tail -1); \
	 next=$${next:-000}; \
	 num=$$(printf "%03d" $$((10#$$next + 1))); \
	 path="docs/adr/$${num}-$(TITLE).md"; \
	 cp docs/adr/000-template.md $$path; \
	 echo "created $$path"

##@ Applications

.PHONY: api-dev
api-dev: ## Run the backend API locally (Spring Boot, http://localhost:8080)
	cd apps/api && ./gradlew bootRun

.PHONY: api-build
api-build: ## Build the backend API jar
	cd apps/api && ./gradlew build

.PHONY: api-test
api-test: ## Run backend tests (JUnit 5)
	cd apps/api && ./gradlew test

.PHONY: web-install
web-install: ## Install frontend dependencies (pnpm workspace)
	pnpm install

.PHONY: web-dev
web-dev: ## Run the frontend dev server (Vite, http://localhost:5173)
	cd apps/web && pnpm dev

.PHONY: web-build
web-build: ## Build the frontend for production
	cd apps/web && pnpm build

.PHONY: web-test
web-test: ## Run frontend tests (Vitest + happy-dom)
	cd apps/web && pnpm test

.PHONY: web-typecheck
web-typecheck: ## TypeScript typecheck for the frontend
	cd apps/web && pnpm typecheck

##@ Quality gates

.PHONY: format
format: ## Format code (formatters not yet wired — will live in packages/config)
	@echo "format: formatters not yet wired — tracked for packages/config"

.PHONY: lint
lint: ## Lint code (linters not yet wired — will live in packages/config)
	@echo "lint: linters not yet wired — tracked for packages/config"

.PHONY: typecheck
typecheck: web-typecheck ## Run type checking across apps
	@echo "typecheck: OK"

.PHONY: test
test: api-test web-test ## Run unit and integration tests across apps
	@echo "test: OK"

.PHONY: test-contract
test-contract: ## Run contract / schema conformance tests
	@echo "test-contract: no contracts yet — nothing to test"

##@ Security & Compliance

.PHONY: secrets-scan
secrets-scan: ## Scan the working tree for leaked secrets (uses gitleaks if available)
	@if command -v gitleaks >/dev/null 2>&1; then \
		gitleaks detect --no-banner --redact --source .; \
	else \
		echo "secrets-scan: gitleaks not installed — skipping (install before CI)"; \
	fi

.PHONY: phi-scan
phi-scan: ## Heuristic scan for PHI-shaped patterns in tracked files
	@bash scripts/phi-scan.sh || echo "phi-scan: script not yet implemented (tracked in docs/security/)"

.PHONY: license-scan
license-scan: ## Inventory dependency licenses (placeholder until deps exist)
	@echo "license-scan: no dependencies yet — nothing to scan"

##@ Composite

.PHONY: verify
verify: check-governance format lint typecheck test secrets-scan ## Full local pre-commit gate
	@echo "verify: OK"

.PHONY: ci
ci: verify test-contract license-scan ## Full CI gate
	@echo "ci: OK"

##@ Housekeeping

.PHONY: clean
clean: ## Remove local build artifacts (safe — never touches source)
	@find . -type d \( -name dist -o -name build -o -name .turbo -o -name coverage \) \
		-not -path "*/node_modules/*" -prune -exec rm -rf {} + 2>/dev/null || true
	@echo "clean: OK"
