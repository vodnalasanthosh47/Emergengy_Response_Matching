# Developer entry points. Run `make help` for the list.
#
# Covers the Go storage tier and the Java services (Maven, JDK 21: set JAVA_HOME to a
# JDK 21 if `mvn -v` shows another version).

GO          ?= go
MVN         ?= mvn
JAVA_SERVICES := request_service resource_service matching_service
GIT_SHA     := $(shell git rev-parse --short HEAD 2>/dev/null || echo dev)
IMAGE_REPO  ?= emergency
GO_MODULES  := storage/coordinator storage/storage_node

COMPOSE     := docker compose -f deploy/compose/docker-compose.yml
COMPOSE_DEV := $(COMPOSE) -f deploy/compose/docker-compose.dev.yml

.DEFAULT_GOAL := help
K8S_DIR     := deploy/k8s
K8S_NS      := emergency
K8S_SERVICES := request-service resource-service matching-service
K8S_CPUS    ?= 6
K8S_MEMORY  ?= 6800
# Gateway API controller; pinned so every machine runs the same version.
ENVOY_GATEWAY_VERSION ?= v1.9.3

.PHONY: help build test test-go test-java images up up-dev down logs ps topology storage-smoke \
	storage-failover storage-resilience storage-e2e request-smoke resource-smoke matching-smoke smoke rematch-smoke \
	k8s-start k8s-gateway-install k8s-images k8s-secrets k8s-up k8s-forward k8s-status k8s-recovery \
	k8s-scaling k8s-storage-failover k8s-e2e k8s-down \
	k8s-clean demo clean

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

# The gateway's nginx.conf is a mounted file: if the gateway was already running, a changed
# config (e.g. a new route) is only picked up after a reload, so `up` always reloads it.
up: ## Start the stack and wait until every container is healthy
	$(COMPOSE) up -d --wait
	$(COMPOSE) exec -T gateway nginx -s reload

up-dev: ## Like `up`, plus the coordinator on 127.0.0.1:9090 and RabbitMQ's UI on 127.0.0.1:15672
	$(COMPOSE_DEV) up -d --wait
	$(COMPOSE_DEV) exec -T gateway nginx -s reload

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

matching-smoke: ## Matching end to end: event-driven match, no double booking, redelivery, POST /match
	BASE_URL=$${BASE_URL:-http://localhost:8080} tests/e2e/matching-smoke.sh

smoke: request-smoke resource-smoke matching-smoke ## All service smoke tests through the gateway (CP1)

rematch-smoke: ## Rematch sweeper: an UNMATCHED request gets matched on its own once a resource appears (waits for the timer; fast with REMATCH_INTERVAL=15s make up-dev)
	BASE_URL=$${BASE_URL:-http://localhost:8080} tests/e2e/rematch-smoke.sh

# ---- Kubernetes (Minikube) -----------------------------------------------------------
# First time:  make k8s-start k8s-gateway-install k8s-images k8s-up
# Then:        make k8s-forward   (in its own terminal) and run any smoke/e2e target.

k8s-start: ## Start Minikube with room for the whole system (6 CPUs, 6.8 GB)
	minikube start --driver=docker --cpus=$(K8S_CPUS) --memory=$(K8S_MEMORY) --disk-size=30g

k8s-gateway-install: ## Install Envoy Gateway (the Gateway API controller and CRDs); once per cluster
	kubectl apply --server-side -f https://github.com/envoyproxy/gateway/releases/download/$(ENVOY_GATEWAY_VERSION)/install.yaml
	kubectl -n envoy-gateway-system rollout status deploy/envoy-gateway --timeout=300s

k8s-images: images ## Build the images and load them (and MongoDB, RabbitMQ if pulled) into Minikube
	@set -e; for i in coordinator storage-node request-service resource-service matching-service; do \
		echo "loading $(IMAGE_REPO)/$$i:local"; minikube image load $(IMAGE_REPO)/$$i:local; \
	done
	@# Third-party images: copy them if Docker has them, otherwise the cluster pulls them.
	@for i in mongo:7.0 rabbitmq:4.1-management; do \
		if docker image inspect $$i >/dev/null 2>&1; then echo "loading $$i"; minikube image load $$i; fi; \
	done

k8s-secrets: ## Create the RabbitMQ credentials Secret (values from deploy/compose/.env, else local defaults)
	kubectl apply -f $(K8S_DIR)/namespace.yaml
	@set -a; if [ -f deploy/compose/.env ]; then . deploy/compose/.env; fi; set +a; \
	kubectl -n $(K8S_NS) create secret generic rabbitmq-credentials \
		--from-literal=username="$${RABBITMQ_USER:-emergency}" \
		--from-literal=password="$${RABBITMQ_PASSWORD:-emergency}" \
		--dry-run=client -o yaml | kubectl apply -f -

k8s-up: k8s-secrets ## Deploy everything to the current Kubernetes context and wait until it is ready
	kubectl apply -k $(K8S_DIR)
	kubectl -n $(K8S_NS) rollout status deploy/coordinator --timeout=180s
	@set -e; for s in storage-0 storage-1 storage-2 rabbitmq; do kubectl -n $(K8S_NS) rollout status sts/$$s --timeout=300s; done
	@set -e; for d in $(K8S_SERVICES); do kubectl -n $(K8S_NS) rollout status deploy/$$d --timeout=300s; done
	kubectl -n $(K8S_NS) wait --for=condition=Programmed gateway/gateway --timeout=180s

k8s-forward: ## Gateway on :8080, coordinator on :9090, RabbitMQ UI on :15672 (runs until Ctrl-C)
	scripts/k8s-port-forward.sh

k8s-status: ## Pods, Deployments, StatefulSets, Services and routes in namespace emergency
	kubectl -n $(K8S_NS) get pods,deploy,sts,svc,pvc -o wide
	kubectl -n $(K8S_NS) get gateway,httproute

k8s-recovery: ## Delete/crash service pods under load: Kubernetes recovers them, no call fails (needs k8s-forward)
	tests/e2e/k8s-pod-recovery.sh

k8s-scaling: ## Scale a service 2 → 4 pods and back under load; new pods serve, no call fails (needs k8s-forward)
	tests/e2e/k8s-scaling.sh

k8s-storage-failover: ## Delete a storage primary pod: replica takes over, recreated pod syncs (needs k8s-forward)
	MODE=k8s tests/e2e/storage-failover.sh

k8s-e2e: smoke k8s-storage-failover k8s-recovery k8s-scaling ## Every Kubernetes check: smoke, storage failover, recovery, scaling

demo: ## The final demonstration (PDF §10), step by step with pauses (needs k8s-forward; DEMO_PAUSE=0, DEMO_FROM=N)
	scripts/demo.sh

k8s-down: ## Remove the deployment but keep the namespace and its volumes (the data)
	kubectl delete -k $(K8S_DIR)/gateway --ignore-not-found
	kubectl delete -k $(K8S_DIR)/services --ignore-not-found
	kubectl delete -k $(K8S_DIR)/storage --ignore-not-found

k8s-clean: k8s-down ## Also delete the namespace with its volumes (deletes stored data!)
	kubectl delete -f $(K8S_DIR)/namespace.yaml --ignore-not-found

clean: ## Remove containers, the MongoDB data volumes and bin/  (deletes stored data!)
	$(COMPOSE_DEV) down -v --remove-orphans
	rm -rf bin/
