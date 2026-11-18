# Developer entry points. Run `make help` for the list.
#
# Currently covers the Go storage tier; the Maven build of the Java services is added
# to build/test/images when those services land.

GO          ?= go
GIT_SHA     := $(shell git rev-parse --short HEAD 2>/dev/null || echo dev)
IMAGE_REPO  ?= emergency
GO_MODULES  := storage/coordinator storage/storage_node

COMPOSE     := docker compose -f deploy/compose/docker-compose.yml
COMPOSE_DEV := $(COMPOSE) -f deploy/compose/docker-compose.dev.yml

.DEFAULT_GOAL := help
K8S_DIR     := deploy/k8s/storage

.PHONY: help build test images up up-dev down logs ps topology storage-smoke storage-failover \
	k8s-images k8s-up k8s-down clean

help: ## Show this help
	@grep -E '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-17s\033[0m %s\n", $$1, $$2}'

build: ## Compile the Go components into bin/
	cd storage/coordinator  && $(GO) build -o ../../bin/coordinator  ./cmd/coordinator
	cd storage/storage_node && $(GO) build -o ../../bin/storage-node ./cmd/storage-node

test: ## Vet and run all Go tests with the race detector (integration tests need Docker)
	@set -e; for m in $(GO_MODULES); do \
		echo "==> $$m"; \
		(cd $$m && $(GO) vet ./... && $(GO) test -race ./...); \
	done

# Every image gets two tags: the git short SHA (immutable, what CP2 loads into
# Minikube) and "local" (what docker-compose.yml uses by default).
images: ## Build Docker images tagged with the git SHA and "local"
	docker build -t $(IMAGE_REPO)/coordinator:$(GIT_SHA)  -t $(IMAGE_REPO)/coordinator:local  storage/coordinator
	docker build -t $(IMAGE_REPO)/storage-node:$(GIT_SHA) -t $(IMAGE_REPO)/storage-node:local storage/storage_node

up: ## Start the stack and wait until every container is healthy
	$(COMPOSE) up -d --wait

up-dev: ## Like `up`, but also publish the coordinator on 127.0.0.1:9090 (3 shards × 2 nodes)
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
