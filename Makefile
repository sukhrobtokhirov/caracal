# Database IDE — development and build commands.
#
# Prerequisites: Go 1.25+, Node.js 20+.

BINARY      := dbide
BUILD_DIR   := bin
VERSION     ?= dev
LDFLAGS     := -s -w -X main.version=$(VERSION)
DEV_PORT    ?= 8080
DEV_ORIGIN  := http://localhost:5173

.PHONY: help dev-backend dev-frontend test test-backend test-frontend build build-frontend build-backend fmt vet clean deps

help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

deps: ## Install frontend dependencies
	cd web && npm install

dev-backend: ## Run the Go server on a fixed port, accepting the Vite dev origin
	go run ./cmd/dbide --port $(DEV_PORT) --dev-origin $(DEV_ORIGIN) --no-open

dev-frontend: ## Run the Vite dev server (proxies /api to the Go server)
	cd web && npm run dev

test: test-backend test-frontend ## Run all tests

test-backend: ## Run Go tests
	go test ./...

test-frontend: ## Type-check and run frontend tests
	cd web && npx tsc --noEmit && npm test

build-frontend: ## Build the SPA into internal/webassets/dist
	find internal/webassets/dist -mindepth 1 ! -name .gitkeep -delete
	cd web && npm ci --no-audit --no-fund && npm run build

build-backend: ## Compile the Go binary with the embedded bundle
	mkdir -p $(BUILD_DIR)
	go build -trimpath -ldflags "$(LDFLAGS)" -o $(BUILD_DIR)/$(BINARY) ./cmd/dbide

build: build-frontend build-backend ## Produce one self-contained executable
	@echo "Built $(BUILD_DIR)/$(BINARY) ($(VERSION))"

fmt: ## Format Go sources
	gofmt -w cmd internal

vet: ## Run go vet
	go vet ./...

clean: ## Remove build output
	rm -rf $(BUILD_DIR)
	find internal/webassets/dist -mindepth 1 ! -name .gitkeep -delete
