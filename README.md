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
| Storage tier: coordinator + 1 storage node on MongoDB (`storage/`) | done |
| Java services (request, resource, matching), RabbitMQ, nginx gateway | next |

## Prerequisites

- **Go 1.26+** (storage tier). If you installed Go under `~/sdk`, put it on your `PATH`:
  `export PATH="$HOME/sdk/go1.27.4/bin:$HOME/go/bin:$PATH"` (add it to `~/.bashrc`).
- **Docker** with Compose v2.20+ (for `up --wait`). The Go integration tests also use
  Docker, via testcontainers.
- JDK 21 + Maven (Java services, coming next). `curl` and `jq` for the smoke test.
- Laptop: about 1 GB free RAM for the storage tier (MongoDB is capped at 512 MB).

## Quickstart (storage tier)

```bash
make test           # go vet + go test -race (starts a throwaway MongoDB container)
make images         # build images tagged <git-sha> and local
make up-dev         # start mongo, storage-node, coordinator; coordinator on 127.0.0.1:9090
make storage-smoke  # end-to-end checks through the coordinator
make down           # stop (data kept);  make clean  also deletes the data volume
```

Run `make help` for all targets.

## Repository layout

```
contracts/openapi/     language-neutral API contracts (the source of truth between Go and Java)
storage/coordinator/   Go — the only entry point to storage; routes to storage nodes
storage/storage_node/  Go — one shard's records in a standalone MongoDB
services/              Java / Spring Boot application services (next)
deploy/compose/        CP1 Docker Compose runtime
tests/e2e/             smoke tests
docs/adr/              architecture decision records
```

Folder names use underscores (`storage_node`, `request_service`). The names used at
runtime (Compose services, images, and later Kubernetes objects) use hyphens
(`storage-node`), because Kubernetes names and hostnames cannot contain underscores.
