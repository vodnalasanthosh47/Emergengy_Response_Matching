# Storage Node (Go + MongoDB)

Stores the records of one shard and applies conditional writes atomically. It is called
**only by the coordinator** (`contracts/openapi/storage-node-api.yaml`). CP1 runs exactly
one node.

MongoDB runs **standalone**, with no replica set and no sharding. It is just this node's
durable, indexed document store. Sharding, replication and recovery are implemented by
our own code (see `docs/adr/0008-mongodb-as-local-engine.md`).

## Layout

| Path | What |
|---|---|
| `cmd/storage-node/` | `main.go` (wiring, index setup with retry, graceful shutdown, `-healthcheck`), `config.go` |
| `internal/api/` | HTTP handlers for `/internal/data/{key}` and `/internal/query`, error JSON, metrics |
| `internal/store/` | `MongoStore` (versioned writes) and `BuildFilter` (query → Mongo filter) |
| `internal/model/` | Contract types and validation |
| `internal/heartbeat/`, `recovery/` | Placeholders for CP2 and CP4 |

## Data model

One collection, `records`:

```js
{ _id: "resource:tdr1yz:8a7c…", value: { …domain document… },
  indexes: { collection: "resource", type: "WATER", status: "AVAILABLE", geoCell: "tdr1yz" },
  version: 7, updatedAt: ISODate("…") }
```

Indexes on `indexes.collection`, `indexes.geoCell`, `indexes.type` and `indexes.status`
are created at startup. Creating them is idempotent.

## Write semantics

Each case is one atomic single-document MongoDB operation:

| `expectedVersion` | MongoDB operation | Outcome |
|---|---|---|
| omitted | `findOneAndUpdate({_id}, {$set…, $inc:{version:1}}, upsert)` | 201 created / 200 replaced |
| `0` | `insertOne` | 201, or **409** on duplicate `_id` |
| `n` | `findOneAndUpdate({_id, version: n}, …)` | 200, or re-read → **412** missing / **409** other version |

DELETE uses the same filter rule via `?expectedVersion=n`.

Two writers holding the same version cannot both succeed, because only one of them can
match `{_id, version: n}`. The integration test
`TestConcurrentCompareAndSetExactlyOneWins` checks this with 20 goroutines.

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `NODE_ID` | `node-0` | Identifier (used in logs; registration in CP2) |
| `MONGO_URI` | `mongodb://localhost:27017` | Local MongoDB (a sidecar in Kubernetes) |
| `MONGO_DB` | `storage` | Database name |
| `MONGO_TIMEOUT` | `2s` | Per-operation timeout; a slow or down Mongo gives `503` |

## Develop

```bash
go test -race ./...         # integration tests start a throwaway MongoDB via testcontainers (needs Docker)
go test -race -short ./...  # unit tests only
```

## Known limitation

When a record is deleted and recreated, its version restarts at 1. A client still holding
a stale "version 1" of the old record could then pass a conditional write against the new
one. This needs tombstones to fix. It doesn't affect the claim flow, which never deletes
resources.
