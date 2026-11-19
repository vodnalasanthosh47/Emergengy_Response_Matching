# Developer entry points. Run `make help` for the list.
#
# Covers the Go storage tier and the Java services (Maven, JDK 21: set JAVA_HOME to a
# JDK 21 if `mvn -v` shows another version).

GO          ?= go
MVN         ?= mvn
JAVA_SERVICES := request_service resource_service
GIT_SHA     := $(shell git rev-parse --short HEAD 2>/dev/null || echo dev)
IMAGE_REPO  ?= emergency
GO_MODULES  := storage/coordinator storage/storage_node

COMPOSE     := docker compose -f deploy/compose/docker-compose.yml
COMPOSE_DEV := $(COMPOSE) -f deploy/compose/docker-compose.dev.yml

.DEFAULT_GOAL := help
K8S_DIR     := deploy/k8s/storage

.PHONY: help build test test-go test-java images up up-dev down logs ps topology storage-smoke \
	storage-failover storage-resilience storage-e2e request-smoke resource-smoke k8s-images k8s-up k8s-down clean

help: ## Show this help
	@grep -E '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-17s\033[0m %s\n", $$1, $$2}'

build: ## Compile the Go components into bin/ and package the Java services
	cd storage/coordinator  && $(GO) build -o ../../bin/coordinator  ./cmd/coordinator
	cd storage/storage_node && $(GO) build -o ../../bin/storage-node ./cmd/storage-node
	$(MVN) -B -q -DskipTests package

test: test-go test-java ## All tests: Go (vet + race) and Java (mvn verify); integration tests need Docker

test-go: ## Vet and run all Go tests with the race detector
	@set -e; for m in $(GO_MODULES); do \
		echo "==> $$m"; \
		(cd $$m && $(GO) vet ./... && $(GO) test -race ./...); \
	done

test-java: ## Run all Java tests (unit, web layer, and RabbitMQ via Testcontainers)
	$(MVN) -B verify

# Every image gets two tags: the git short SHA (immutable, what CP2 loads into
# Minikube) and "local" (what docker-compose.yml uses by default).
images: ## Build Docker images tagged with the git SHA and "local"
	docker build -t $(IMAGE_REPO)/coordinator:$(GIT_SHA)  -t $(IMAGE_REPO)/coordinator:local  storage/coordinator
	docker build -t $(IMAGE_REPO)/storage-node:$(GIT_SHA) -t $(IMAGE_REPO)/storage-node:local storage/storage_node
	@set -e; for s in $(JAVA_SERVICES); do \
		name=$$(echo $$s | tr _ -); \
		docker build -f services/$$s/Dockerfile -t $(IMAGE_REPO)/$$name:$(GIT_SHA) -t $(IMAGE_REPO)/$$name:local .; \
	done

up: ## Start the stack and wait until every container is healthy
	$(COMPOSE) up -d --wait

up-dev: ## Like `up`, plus the coordinator on 127.0.0.1:9090 and RabbitMQ's UI on 127.0.0.1:15672
	$(COMPOSE_DEV) up -d --wait

down: ## Stop and remove the containers (data volume is kept)
	$(COMPOSE_DEV) down

logs: ## Follow container logs
	$(COMPOSE) logs -f --tail=100

ps: ## Show container status and health
	$(COMPOSE) ps

topology: ## Show shards, node roles/states and record counts (needs `make up-dev`)
	@curl -sS $${BASE_URL:-http://localhost:9090}/internal/topology | jq -r '.shards[] | "shard \(.shard)  primary=\(.primary // "NONE")", (.nodes[] | "  \(.nodeId)\t\(.role)\t\(.state)\trecords=\(.records // "-")")'

storage-smoke: ## Smoke-test the storage tier through the coordinator (needs `make up-dev`)
	BASE_URL=$${BASE_URL:-http://localhost:9090} tests/e2e/storage-smoke.sh

storage-failover: ## Distribution + kill a primary + recovery test (needs `make up-dev`; SHARD=0..2)
	BASE_URL=$${BASE_URL:-http://localhost:9090} tests/e2e/storage-failover.sh

storage-resilience: ## Concurrent claims, MongoDB failure, coordinator and full restarts (needs `make up-dev`)
	BASE_URL=$${BASE_URL:-http://localhost:9090} tests/e2e/storage-resilience.sh

storage-e2e: storage-smoke storage-failover storage-resilience ## All storage end-to-end tests (needs `make up-dev`)

request-smoke: ## Request Service end to end through the gateway (needs `make up-dev`)
	BASE_URL=$${BASE_URL:-http://localhost:8080} tests/e2e/request-smoke.sh

resource-smoke: ## Resource Service end to end through the gateway (needs `make up-dev`)
	BASE_URL=$${BASE_URL:-http://localhost:8080} tests/e2e/resource-smoke.sh

k8s-images: images ## Load the images (and mongo:7.0) into Minikube
	minikube image load $(IMAGE_REPO)/coordinator:local
	minikube image load $(IMAGE_REPO)/storage-node:local
	minikube image load mongo:7.0

k8s-up: ## Deploy the storage tier to the current Kubernetes context (namespace emergency)
	kubectl apply -k $(K8S_DIR)
	kubectl -n emergency rollout status deploy/coordinator --timeout=180s
	for s in 0 1 2; do kubectl -n emergency rollout status sts/storage-$$s --timeout=300s; done

k8s-down: ## Remove the storage tier from Kubernetes (PVCs, i.e. data, are kept)
	kubectl delete -k $(K8S_DIR) --ignore-not-found

clean: ## Remove containers, the MongoDB data volumes and bin/  (deletes stored data!)
	$(COMPOSE_DEV) down -v --remove-orphans
	rm -rf bin/
