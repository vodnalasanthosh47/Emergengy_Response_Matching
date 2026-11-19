# Emergengy_Response_Matching

A distributed emergency resource matching system that connects people requiring emergency
assistance (water, food, medicine, transport, shelter) with available resources offered
by volunteers, NGOs, government depots or individuals.

```
Client → Gateway → Request Service → RabbitMQ → Matching Service → Coordinator → Storage → Resource assigned
```

## Status: Cloud Computing scope done (Compose and Kubernetes)

| Part | State |
|---|---|
| Contracts: coordinator + storage-node APIs (`contracts/openapi/`) | done |
| Storage tier: coordinator + **3 shards × (primary + replica)** on MongoDB (`storage/`) | done |
| Geohash sharding, replicated writes, heartbeats, failover, recovery sync | done |
| Storage tier on Kubernetes (`deploy/k8s/storage/`) | done, tested on Minikube |
| `common` Java module (storage client, geohash, shared types), RabbitMQ, nginx gateway | done |
| Request Service (`services/request_service`) | done |
| Resource Service (`services/resource_service`) | done |
| Matching Service (`services/matching_service`) | done |
| Whole system on Kubernetes (Minikube): services ×2 pods, RabbitMQ, Gateway API (`deploy/k8s/`) | done |
| Kubernetes demos: pod recovery, scaling, storage failover by deleting pods (`make k8s-e2e`) | done |

### Storage tier against the problem statement

| PDF requirement | Where |
|---|---|
| §2/§4: 1 coordinator, 3 shards, ≥1 replica per shard | Compose: `storage-{0,1,2}-{a,b}`. Kubernetes: one StatefulSet per shard, 2 pods each |
| §4: the coordinator picks the shard by hash; the client doesn't know the location | Requests `crc32(requestId) mod 3`, resources `crc32(geoCell) mod 3` (`storage/coordinator/internal/routing`, ADR 0006) |
| §6: register, heartbeat and data internal APIs | `contracts/openapi/` |
| §8: detect failed nodes, stop routing to them | 3 missed heartbeats → DOWN (`internal/registry`) |
| §8: use a replica / keep processing | Reads fall back to a replica; the replica is promoted to primary |
| §8: sync a recovered node | `internal/replication` (copy pass, then repair pass) |
| §10/§11: distribution, shard selection, failure, recovery demo and tests | `make storage-failover` (Compose); `MODE=k8s` (pod deletion) |
| §11: simultaneous requests (no double-booking), restarts | `make storage-resilience` |

## Prerequisites

- **Go 1.26+** (storage tier). If you installed Go under `~/sdk`, put it on your `PATH`:
  `export PATH="$HOME/sdk/go1.27.4/bin:$HOME/go/bin:$PATH"` (add it to `~/.bashrc`).
- **Docker** with Compose v2.20+ (for `up --wait`). The Go integration tests also use
  Docker, via testcontainers.
- **JDK 21 + Maven 3.9** (Java services; Spring Boot 4.1). If `mvn -v` shows another
  Java, point `JAVA_HOME` at a JDK 21 (macOS: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`).
  The Java integration tests start RabbitMQ in Docker (Testcontainers).
- For the end-to-end scripts: bash 4+ (macOS: `brew install bash`), `curl`, `jq`, `python3`.
- Laptop: about 3.5 GB free RAM for the whole stack (6 MongoDB instances at 384 MB each,
  RabbitMQ and each Java service at 512 MB). For Kubernetes: `minikube` and `kubectl`, and
  Docker Desktop with at least ~7 GB of memory (Minikube gets 6.8 GB).
- Optional: copy `deploy/compose/.env.example` to `deploy/compose/.env` to set your own
  RabbitMQ credentials (local defaults are used otherwise).
- MongoDB **7.0** is used everywhere: MongoDB 8 refuses to start on Linux kernels
  6.19.0–7.0.13 (SERVER-121912).

## Quickstart

```bash
make test              # Go (vet + race, throwaway MongoDB) and Java (mvn verify, throwaway RabbitMQ)
make images            # build images tagged <git-sha> and local
make up-dev            # gateway on :8080, request service, RabbitMQ (UI on 127.0.0.1:15672),
                       # coordinator (127.0.0.1:9090) + 3 shards × 2 nodes
make request-smoke     # Request Service end to end through the gateway
make resource-smoke    # Resource Service end to end through the gateway (incl. shard placement)
make matching-smoke    # request → RabbitMQ → matching → MATCHED; no double booking; redelivery
make smoke             # all three service smoke tests (spec §7.1-H)
make topology          # shards, primaries, node states, record counts
make storage-smoke     # data API checks through the coordinator
make storage-failover  # distribution, then stop a primary, keep working, restart it, verify sync
make storage-resilience # concurrent claims, MongoDB failure, coordinator + full restarts
                       # (make storage-e2e runs all three)
make down              # stop (data kept);  make clean  also deletes the data volumes
```

Kubernetes (Minikube; details, design choices and the demo in [`deploy/k8s/README.md`](deploy/k8s/README.md)):

```bash
make k8s-start k8s-gateway-install   # once: Minikube (6 CPUs, 6.8 GB) and the Gateway API controller
make k8s-images k8s-up               # load the images, deploy everything, wait until ready
make k8s-forward                     # own terminal: gateway :8080, coordinator :9090, RabbitMQ UI :15672
make k8s-e2e                         # smoke tests, storage failover, pod recovery, scaling
```

Run `make help` for all targets.

## Repository layout

```
contracts/openapi/     language-neutral API contracts (the source of truth between Go and Java)
storage/coordinator/   Go — the only entry point to storage: sharding, replication, failover, recovery
storage/storage_node/  Go — one copy of one shard's records in a standalone MongoDB
common/                Java — code the services share: storage client, geohash, shared types, events
services/              Java / Spring Boot application services (request_service, resource_service, matching_service)
                       each has a README with its status and what is left
deploy/compose/        Docker Compose runtime and the nginx gateway
deploy/k8s/            Kubernetes: storage/, services/ (RabbitMQ + 3 services), gateway/ (Gateway API)
scripts/               helpers: k8s-port-forward.sh (make k8s-forward)
tests/e2e/             end-to-end scripts
docs/data-format.md    how requests and resources are stored: keys, records, indexes (start here)
docs/service-interfaces.md  what each service relies on from the others, and a change log
docs/adr/              architecture decision records
```

Folder names use underscores (`storage_node`, `request_service`). The names used at
runtime (Compose services, images, and later Kubernetes objects) use hyphens
(`storage-node`), because Kubernetes names and hostnames cannot contain underscores.
