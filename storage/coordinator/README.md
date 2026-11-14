# Coordinator (Go)

The **only** entry point to storage. Application services call the coordinator data API
(`contracts/openapi/coordinator-api.yaml`); they never talk to a storage node or MongoDB.
The coordinator holds no application data, only soft state.

**CP1:** one storage node, no sharding or replication. Every `/v1/...` call is forwarded
to the matching `/internal/...` call on the node at `STORAGE_NODE_URL`.

```
services ──/v1/data, /v1/query──► coordinator ──/internal/...──► storage-node ──► mongo
```

## Layout

| Path | What |
|---|---|
| `cmd/coordinator/` | `main.go` (wiring, graceful shutdown, `-healthcheck`), `config.go` (env vars) |
| `internal/api/` | HTTP handlers for the data API, error JSON, Prometheus metrics |
| `internal/store/` | `RecordStore` interface + `Forwarder` (CP1 single-node implementation) |
| `internal/model/` | Contract types and validation (a deliberate copy of the storage node's) |
| `internal/routing/`, `registry/`, `replication/`, `failover/` | Placeholders for CP2–CP3 |

**The seam for CP2:** handlers depend only on `store.RecordStore`. Ring routing across
three nodes will be a new `RecordStore` implementation, selected in `main.go`. The
handlers and the HTTP contract stay as they are.

## Behaviour

- **Versioning lives on the storage node.** The coordinator passes `expectedVersion`
  through and relays the node's `404`/`409`/`412` unchanged.
- **Errors:**
  - A timeout, refused connection or 5xx from the node becomes `503 STORAGE_UNAVAILABLE`.
  - Invalid input gets `400` before anything is forwarded.
- **Endpoints:**
  - `/healthz`: liveness (process up).
  - `/readyz`: the storage node is ready.
  - `/metrics`: request count and latency per route, `coordinator_version_conflicts_total`,
    `coordinator_upstream_errors_total`.

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `STORAGE_NODE_URL` | *(required)* | Base URL of the single storage node (CP1 only) |
| `STORAGE_TIMEOUT` | `3s` | Per-call timeout to the storage node |

## Develop

```bash
go test -race ./...                       # handler tests use a fake RecordStore; forwarder tests use httptest
STORAGE_NODE_URL=http://localhost:8081 go run ./cmd/coordinator
```
