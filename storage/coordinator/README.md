# Coordinator (Go)

The **only** entry point to storage. Application services call the coordinator data API
(`contracts/openapi/coordinator-api.yaml`); they never talk to a storage node or MongoDB,
and never learn where a record is stored. The coordinator holds no application data,
only an in-memory registry of the storage nodes.

```
services ──/v1/data, /v1/query──► coordinator ──/internal/...──► shard 0: storage-0-a (PRIMARY), storage-0-b (REPLICA)
                                       ▲                       ► shard 1: storage-1-a (PRIMARY), storage-1-b (REPLICA)
                                       └── register/heartbeat ─ ► shard 2: storage-2-a (PRIMARY), storage-2-b (REPLICA)
```

## How it works

| Concern | Where | Behaviour |
|---|---|---|
| **Sharding** | `internal/routing` | Keys are `{collection}:{homeCell}:{uuid}`. Shard = `crc32(homeCell) mod 3`. `homeCell` is the geohash of the entity's location at creation and never changes, so records never move and everything created in one cell lives together. CRC32 is stable across restarts. |
| **Membership** | `internal/registry` | Nodes `POST /internal/nodes/register` on startup and `/internal/nodes/heartbeat` every 5 s, sending `nodeId`, `shardId`, `url`, and their newest `updatedAt`. |
| **Failure detection** | `internal/registry` | A sweeper runs every second. A node silent for 15 s (3 heartbeats) is marked **DOWN** and gets no more traffic. A call that fails also marks the node DOWN at once. A late heartbeat from a DOWN node gets `404`, and the node registers again. |
| **Primary election** | `internal/registry` | A shard with no primary (cold start, coordinator restart, whole shard was down) waits 10 s for nodes, then elects the registering node with the newest data. |
| **Writes** | `internal/store/sharded.go` | Sent to the shard's primary, the only node that checks `expectedVersion`. The primary's result is copied to every ACTIVE and SYNCING replica **before** the caller gets an answer. A replica that fails the copy is marked DOWN. So every ACTIVE replica holds every acknowledged write. |
| **Reads** | `internal/store/sharded.go` | Go to the primary. If it is unreachable, the next ACTIVE replica answers in the same call. SYNCING nodes never serve reads. |
| **Queries** | `internal/store/sharded.go` | Scatter-gather: sent to all 3 shards in parallel, merged in key order, cut to `limit`. If a shard has no readable node, the query returns `503`. |
| **Failover** | `internal/registry` | When a primary goes DOWN, the ACTIVE replica with the newest data is promoted. No acknowledged write is lost. |
| **Recovery** | `internal/replication` | A node that registers into a shard with a primary becomes **SYNCING**: it receives new writes but serves no reads. Pass 1 copies everything the primary changed since the node's newest write minus 30 s. Pass 2 repairs writes the node holds but the primary never acknowledged. Then the node is marked **ACTIVE**. |

Node states: `ELECTING` → (elected) `ACTIVE PRIMARY`, or `SYNCING` → `ACTIVE REPLICA`.
Any state can become `DOWN`, and from there the node re-registers.

**Limitations:**
- One coordinator replica. The registry is soft state, so after a coordinator restart
  data calls return `503` for one election window (~10–15 s), until nodes re-register.
- If a shard's primary dies while its only replica is still SYNCING, the shard stays
  unavailable until a node with the full data returns. An incomplete copy is never
  promoted.

## Layout

| Path | What |
|---|---|
| `cmd/coordinator/` | `main.go` (wiring, graceful shutdown, `-healthcheck`), `config.go` (env vars) |
| `internal/api/` | HTTP handlers: data API, node registration and heartbeat, topology, error JSON, metrics |
| `internal/routing/` | key → shard (geohash `homeCell`, CRC32) |
| `internal/registry/` | Node registry, failure detection, election, failover |
| `internal/store/` | `RecordStore` interface, `ShardedStore` (routing, replication, read fallback, scatter-gather), `Forwarder` (HTTP client for one node), `Pool` |
| `internal/replication/` | Recovery `Syncer` (copy and repair passes) |
| `internal/fakenode/` | In-memory storage node used by the tests |
| `internal/model/` | Contract types and validation (a deliberate copy of the storage node's) |

## Endpoints

- **Data API:** `/v1/data/{key}`, `/v1/query`. Unchanged since CP1.
- **Node endpoints:** `POST /internal/nodes/register`, `POST /internal/nodes/heartbeat`.
- **`GET /internal/topology`:** every shard with its primary, and each node's role,
  state, last heartbeat and record count. `make topology` prints it.
- **Health:**
  - `/healthz`: liveness.
  - `/readyz`: every shard has a primary.
- **`/metrics`:** request count and latency per route, plus:
  - `coordinator_version_conflicts_total`
  - `coordinator_upstream_errors_total`
  - `coordinator_storage_nodes{state}`
  - `coordinator_failovers_total`

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `SHARD_COUNT` | `3` | Number of shards |
| `STORAGE_TIMEOUT` | `3s` | Per-call timeout to a storage node |
| `HEARTBEAT_TIMEOUT` | `15s` | Silence after which a node is DOWN (3 × the nodes' 5 s interval) |
| `SWEEP_INTERVAL` | `1s` | How often failure detection runs |
| `ELECTION_WINDOW` | `10s` | Wait for nodes before electing a shard's primary |
| `SYNC_OVERLAP` | `30s` | Window before a node's newest write that recovery re-copies (absorbs clock skew) |

## Develop

```bash
go test -race ./...   # registry (fake clock), routing, sharded store + recovery against in-memory fake nodes
```
