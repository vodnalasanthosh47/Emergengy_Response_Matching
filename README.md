# Emergengy_Response_Matching

A distributed emergency resource matching system that connects people requiring emergency
assistance (water, food, medicine, transport, shelter) with available resources offered
by volunteers, NGOs, government depots or individuals.

```
Client → Gateway → Request Service → RabbitMQ → Matching Service → Coordinator → Storage → Resource assigned
```

## Status: Checkpoint 1 in progress

| Part | State |
|---|---|
| Contracts: coordinator + storage-node APIs (`contracts/openapi/`) | done |
| Storage tier: coordinator + **3 shards × (primary + replica)** on MongoDB (`storage/`) | done |
| Geohash sharding, replicated writes, heartbeats, failover, recovery sync | done |
| Storage tier on Kubernetes (`deploy/k8s/storage/`) | done |
| Java services (request, resource, matching), RabbitMQ, nginx gateway | next |

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
- JDK 21 + Maven (Java services, coming next).
- For the end-to-end scripts: bash 4+ (macOS: `brew install bash`), `curl`, `jq`, `python3`.
- Laptop: about 2.5 GB free RAM for the storage tier (6 MongoDB instances, each capped at 384 MB).
- MongoDB **7.0** is used everywhere: MongoDB 8 refuses to start on Linux kernels
  6.19.0–7.0.13 (SERVER-121912).

## Quickstart (storage tier)

```bash
make test              # go vet + go test -race (starts a throwaway MongoDB container)
make images            # build images tagged <git-sha> and local
make up-dev            # coordinator + 3 shards × 2 nodes (+ their MongoDBs); coordinator on 127.0.0.1:9090
make topology          # shards, primaries, node states, record counts
make storage-smoke     # data API checks through the coordinator
make storage-failover  # distribution, then stop a primary, keep working, restart it, verify sync
make storage-resilience # concurrent claims, MongoDB failure, coordinator + full restarts
                       # (make storage-e2e runs all three)
make down              # stop (data kept);  make clean  also deletes the data volumes
```

Kubernetes (Minikube):

```bash
make k8s-images && make k8s-up
kubectl -n emergency port-forward svc/coordinator 9091:8080 &
MODE=k8s BASE_URL=http://localhost:9091 tests/e2e/storage-failover.sh   # kills pods instead of containers
```

Run `make help` for all targets.

## Repository layout

```
contracts/openapi/     language-neutral API contracts (the source of truth between Go and Java)
storage/coordinator/   Go — the only entry point to storage: sharding, replication, failover, recovery
storage/storage_node/  Go — one copy of one shard's records in a standalone MongoDB
services/              Java / Spring Boot application services (next)
deploy/compose/        Docker Compose runtime
deploy/k8s/storage/    Kubernetes manifests for the storage tier
tests/e2e/             smoke tests
docs/data-format.md    how requests and resources are stored: keys, records, indexes (start here)
docs/adr/              architecture decision records
```

Folder names use underscores (`storage_node`, `request_service`). The names used at
runtime (Compose services, images, and later Kubernetes objects) use hyphens
(`storage-node`), because Kubernetes names and hostnames cannot contain underscores.
