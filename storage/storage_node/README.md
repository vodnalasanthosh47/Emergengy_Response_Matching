# Storage Node (Go + MongoDB)

Holds one copy of one shard, and applies conditional writes atomically. It is called
**only by the coordinator** (`contracts/openapi/storage-node-api.yaml`). The system runs
3 shards × 2 nodes. The coordinator decides which node of a shard is the primary. The
node itself just stores what it is told and reports its liveness.

MongoDB runs **standalone**, with no replica set and no sharding. It is just this node's
durable, indexed document store. Sharding, replication and recovery are implemented by
our own code (see `docs/adr/0008-mongodb-as-local-engine.md`). We use **MongoDB 7.0**:
MongoDB 8 refuses to start on Linux kernels 6.19.0–7.0.13 (SERVER-121912).

## Layout

| Path | What |
|---|---|
| `cmd/storage-node/` | `main.go` (wiring, index setup with retry, heartbeat, graceful shutdown, `-healthcheck`), `config.go` |
| `internal/api/` | HTTP handlers (data, replica, repair, sync, stats), error JSON, metrics |
| `internal/store/` | `MongoStore`: versioned writes and tombstones (`mongo.go`), replica, repair and sync operations (`replica.go`), and `BuildFilter` |
| `internal/heartbeat/` | Registers with the coordinator, then heartbeats every `HEARTBEAT_INTERVAL`. Registers again on a `404`. |
| `internal/model/` | Contract types and validation |

## Data model

One collection, `records`:

```js
{ _id: "resource:tdr1y:8a7c…", value: { …domain document… },
  indexes: { collection: "resource", type: "WATER", status: "AVAILABLE", geoCell: "tdr1y" },
  version: 7, updatedAt: ISODate("…"), deleted: false }
```

Indexes on `indexes.collection`, `indexes.geoCell`, `indexes.type`, `indexes.status` and
`(updatedAt, _id)` (for recovery sync) are created at startup. Creating them is idempotent.

**Deletes are tombstones.** A delete sets `deleted: true`, clears `value`, and bumps the
version. Tombstones are hidden from `/internal/data` and `/internal/query`. They exist so
that deletes replicate and resync like any other write. Re-creating a deleted key keeps
counting versions up, so a client holding a stale version can never overwrite the new
record.

## Write semantics (`/internal/data`, primary)

Each case is one atomic single-document MongoDB operation:

| `expectedVersion` | MongoDB operation | Outcome |
|---|---|---|
| omitted | `findOneAndUpdate({_id}, {$set…, $inc:{version:1}}, upsert)` | 201 created (or revived tombstone) / 200 replaced |
| `0` | `findOneAndUpdate({_id, deleted:true}, …, upsert)` | 201, or **409** if a live record exists (duplicate `_id`) |
| `n` | `findOneAndUpdate({_id, version: n, deleted≠true}, …)` | 200, or re-read → **412** missing / **409** other version |

`DELETE` uses the same filter rule via `?expectedVersion=n` and returns the tombstone.

## Replication and recovery endpoints (coordinator only)

| Endpoint | Rule |
|---|---|
| `PUT /internal/replica/{key}` | Store the primary's record as-is if its version is ≥ the stored one. Older copies are ignored, which makes replays and out-of-order delivery harmless. |
| `GET /internal/replica/{key}` | Read a record including tombstones |
| `PUT` / `DELETE /internal/repair/{key}?expectedLocalVersion=n` | Overwrite or remove the key, but only if this node still holds version `n` |
| `GET /internal/sync?since=&afterKey=&limit=` | Records changed after a cursor, ordered by `(updatedAt, _id)` |
| `GET /internal/stats` | Live and tombstone counts, newest `updatedAt` |

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `NODE_ID` | *(required)* | Unique node name (in Kubernetes: the pod name) |
| `SHARD_ID` | *(required)* | Shard this node holds a copy of |
| `COORDINATOR_URL` | *(required)* | Where to register and heartbeat |
| `ADVERTISE_URL` | *(required)* | How the coordinator reaches this node |
| `HEARTBEAT_INTERVAL` | `5s` | Heartbeat period |
| `MONGO_URI` | `mongodb://localhost:27017` | Local MongoDB (a sidecar in Kubernetes) |
| `MONGO_DB` | `storage` | Database name |
| `MONGO_TIMEOUT` | `2s` | Per-operation timeout; a slow or down Mongo gives `503` |

## Develop

```bash
go test -race ./...         # integration tests start a throwaway MongoDB 7.0 via testcontainers (needs Docker)
go test -race -short ./...  # unit tests only
```
