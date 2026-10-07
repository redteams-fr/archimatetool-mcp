# Local build of the Archi MCP plugin.
#
# Uses the local JDK when it is Java 21 or newer, otherwise builds in Docker
# (eclipse-temurin:21-jdk). Force a mode with USE_DOCKER=1 or USE_DOCKER=0.
#
#   make              help
#   make build        plugin + unit tests -> build/dist/archi-mcp-<version>.archiplugin
#   make install      copy the plugin into Archi's dropins folder (then restart Archi)
#   make VERSION=1.2.0 build
#   make build ARCHI_HOME=/Applications/Archi.app/Contents/Eclipse   (local JDK only)

SHELL := bash
.DEFAULT_GOAL := help

ARCHI_VERSION ?= 5.10.0
DROPINS       ?= $(HOME)/.archi/dropins
VERSION       ?=
SDK           := .archi-sdk/Archi/plugins
BUNDLE_GLOB   := build/libs/fr.redteams.archi.mcp_*.jar

JAVA_MAJOR := $(shell java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1)
ifeq ($(USE_DOCKER),)
  USE_DOCKER := $(if $(shell [ "$(JAVA_MAJOR)" -ge 21 ] 2>/dev/null && echo yes),0,1)
endif

GRADLE_ARGS := $(if $(VERSION),-Pversion=$(VERSION))

# With a local JDK, ARCHI_HOME (e.g. /Applications/Archi.app/Contents/Eclipse) is used
# instead of the downloaded SDK; the Docker build always uses .archi-sdk.
SDK_DEP := $(if $(and $(ARCHI_HOME),$(filter 0,$(USE_DOCKER))),,$(SDK))
ifeq ($(USE_DOCKER),1)
  GRADLE := ./scripts/build-with-docker.sh
else
  GRADLE := ./gradlew
endif

.PHONY: help build package test install uninstall e2e e2e-remote clients sdk clean distclean info

help: ## Show this help
	@echo "Archi MCP plugin - local build"
	@echo
	@grep -hE '^[a-z0-9-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*## "}; {printf "  make %-12s %s\n", $$1, $$2}'
	@echo
	@echo "Variables: VERSION=1.2.0  USE_DOCKER=0|1  ARCHI_HOME=<Archi install>  DROPINS=$(DROPINS)"
	@echo "Build mode: $(if $(filter 1,$(USE_DOCKER)),Docker (no local JDK 21),local JDK $(JAVA_MAJOR))"

info: ## Show the build mode and the detected Java version
	@echo "Java: $(or $(JAVA_MAJOR),not found)  ->  $(if $(filter 1,$(USE_DOCKER)),Docker build,local build with ./gradlew)"

sdk: $(SDK) ## Download Archi (compile-time SDK) into .archi-sdk

$(SDK):
	./scripts/fetch-archi.sh $(ARCHI_VERSION)

build: $(SDK_DEP) ## Build the plugin and run the unit tests
	$(GRADLE) build $(GRADLE_ARGS)
	@echo; echo "Plugin: $$(ls build/dist/*.archiplugin)"

package: $(SDK_DEP) ## Build the plugin without running the tests
	$(GRADLE) assemble $(GRADLE_ARGS)
	@echo; echo "Plugin: $$(ls build/dist/*.archiplugin)"

test: $(SDK_DEP) ## Run the unit tests
	$(GRADLE) test

install: ## Install the built plugin into Archi's dropins folder
	@ls $(BUNDLE_GLOB) >/dev/null 2>&1 || $(MAKE) --no-print-directory package
	@mkdir -p "$(DROPINS)"
	@rm -f "$(DROPINS)"/fr.redteams.archi.mcp_*.jar
	cp $(BUNDLE_GLOB) "$(DROPINS)/"
	@echo "Installed into $(DROPINS) - restart Archi to load it."

uninstall: ## Remove the plugin from Archi's dropins folder
	rm -f "$(DROPINS)"/fr.redteams.archi.mcp_*.jar
	@echo "Removed - restart Archi."

e2e: build ## End-to-end test in headless Archi (Docker), server on 127.0.0.1
	./e2e/run.sh

e2e-remote: build ## End-to-end test with the server on 0.0.0.0
	./e2e/run.sh --remote

clients: build ## Check Claude Code, OpenCode and Mistral Vibe against the plugin (Docker)
	./e2e/clients.sh

clean: ## Delete the build output
	rm -rf build

distclean: clean ## Also delete the downloaded Archi SDK and Gradle state
	rm -rf .archi-sdk .gradle
