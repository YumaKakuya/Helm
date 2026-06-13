# MCPHUB Makefile — BL-05: One-command build for Java + Go + TS
# Usage:
#   make          — build all (jar + binary + adapters)
#   make test     — run all tests
#   make install  — build + install to ~/.local
#   make clean    — remove all build artifacts
#   make dev      — build + start daemon in foreground
#   make status   — check daemon health

# ──────────────────────────────────────────────────
# Configuration
# ──────────────────────────────────────────────────

REPO_ROOT  := $(shell pwd)
GO_BIN     := $(REPO_ROOT)/mcphub
JAR_DIR    := $(REPO_ROOT)/java/build/libs
JAR_NAME   := mcphub-core.jar
GRADLEW    := $(REPO_ROOT)/java/gradlew
ADAPTER_DIR := $(REPO_ROOT)/adapters
PRODUCT_NAME ?= mcphub
VERSION ?=

# Install paths (override with PREFIX=...)
PREFIX     ?= $(HOME)/.local/share/mcphub
BIN_PREFIX ?= $(HOME)/.local/bin

.PHONY: all jar go-build adapters test test-java test-go clean install dev status stop release curated-export help

# ──────────────────────────────────────────────────
# Default target
# ──────────────────────────────────────────────────

all: jar go-build adapters
	@echo ""
	@echo "Build complete."
	@echo "  JAR:     java/build/libs/$(JAR_NAME)"
	@echo "  Binary:  ./mcphub"
	@echo "  Adapters: adapters/dist/"
	@echo ""
	@echo "Run 'make install' to install, or './mcphub start' to run."

# ──────────────────────────────────────────────────
# Java (Gradle fat-JAR)
# ──────────────────────────────────────────────────

jar:
	@echo "==> Building Java fat-JAR..."
	cd java && ./gradlew jar --quiet
	@# Rename versioned JAR to stable name for Go launcher
	@JAR=$$(find $(JAR_DIR) -name "mcphub-core-*.jar" ! -name "*plain*" | head -1); \
	if [ -n "$$JAR" ] && [ "$$(basename $$JAR)" != "$(JAR_NAME)" ]; then \
		cp "$$JAR" "$(JAR_DIR)/$(JAR_NAME)"; \
	fi
	@echo "    $(JAR_DIR)/$(JAR_NAME)"

# ──────────────────────────────────────────────────
# Go (JRE launcher binary)
# ──────────────────────────────────────────────────

go-build:
	@echo "==> Building Go launcher..."
	go build -o $(GO_BIN) ./cmd/mcphub
	@echo "    $(GO_BIN)"

# ──────────────────────────────────────────────────
# TypeScript adapters
# ──────────────────────────────────────────────────

adapters: $(ADAPTER_DIR)/node_modules
	@echo "==> Building TypeScript adapters..."
	cd $(ADAPTER_DIR) && npx tsc --build
	@echo "    $(ADAPTER_DIR)/dist/"

$(ADAPTER_DIR)/node_modules:
	@echo "==> Installing adapter dependencies..."
	cd $(ADAPTER_DIR) && npm install --silent

# ──────────────────────────────────────────────────
# Tests
# ──────────────────────────────────────────────────

test: test-java test-go
	@echo ""
	@echo "All tests passed."

test-java:
	@echo "==> Running Java tests..."
	cd java && ./gradlew test --quiet

test-go:
	@echo "==> Running Go tests..."
	go test ./...

# ──────────────────────────────────────────────────
# Install
# ──────────────────────────────────────────────────

install: all
	@echo "==> Installing MCPHUB..."
	./install.sh --prefix $(PREFIX)

# ──────────────────────────────────────────────────
# Development
# ──────────────────────────────────────────────────

dev: all
	@echo "==> Starting MCPHUB (foreground)..."
	$(GO_BIN) start --no-daemon

status:
	@$(GO_BIN) health 2>/dev/null || echo "mcphub: not running"

stop:
	@$(GO_BIN) stop 2>/dev/null || echo "mcphub: not running"

# ──────────────────────────────────────────────────
# Release
# ──────────────────────────────────────────────────

release:
	@echo "==> Building release archives..."
	PRODUCT_NAME=$(PRODUCT_NAME) ./scripts/release-build.sh $(VERSION)

curated-export:
	@echo "==> Building curated export..."
	./scripts/curated-export.sh

# ──────────────────────────────────────────────────
# Clean
# ──────────────────────────────────────────────────

clean:
	@echo "==> Cleaning build artifacts..."
	rm -f $(GO_BIN)
	cd java && ./gradlew clean --quiet 2>/dev/null || true
	rm -rf $(ADAPTER_DIR)/dist
	rm -rf dist/release
	@echo "    Done."

# ──────────────────────────────────────────────────
# Help
# ──────────────────────────────────────────────────

help:
	@echo "MCPHUB Build System"
	@echo ""
	@echo "Targets:"
	@echo "  make          Build all (jar + binary + adapters)"
	@echo "  make test     Run all tests (Java + Go)"
	@echo "  make jar      Build Java fat-JAR only"
	@echo "  make go-build Build Go launcher only"
	@echo "  make adapters Build TypeScript adapters only"
	@echo "  make install  Build + install to ~/.local"
	@echo "  make dev      Build + run daemon (foreground)"
	@echo "  make status   Check daemon health"
	@echo "  make stop     Stop daemon"
	@echo "  make release  Build release archives (all platforms)"
	@echo "  make curated-export  Build and verify curated export tree"
	@echo "  make clean    Remove all build artifacts"
	@echo "  make help     Show this message"
	@echo ""
	@echo "Prerequisites: Java 21+, Go 1.25+, Node 20+"
