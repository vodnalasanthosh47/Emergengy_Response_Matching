# Storage Schema (MongoDB inside each storage node)

This document describes how records are stored in the MongoDB instance that backs a
storage node. Only the storage-node process talks to this database. The application
services and the coordinator see records through the HTTP contracts in
`contracts/openapi/`, and never see MongoDB itself.

Source of truth in code:
- `storage/storage_node/internal/store/mongo.go`: document shape, writes, indexes
- `storage/storage_node/internal/store/filter.go`: query → MongoDB filter
- `storage/storage_node/internal/model/model.go`: validation rules

MongoDB does **not** enforce this schema (there is no `$jsonSchema` validator on the
collection). The storage-node code enforces it on every write.

---

## 1. Location

| Item       | Value                                                          |
|------------|----------------------------------------------------------------|
| Engine     | Standalone `mongod` 7.0 (no replica set, no sharding — see ADR 0008; not 8.x, which refuses to start on Linux 6.19–7.0.13, SERVER-121912) |
| Database   | `storage` (configurable via `MONGO_DB`)                        |
| Collection | `records` (the only collection; requests and resources share it) |

---

## 2. Document fields

| Field       | BSON type                  | Set by  | Description |
|-------------|----------------------------|---------|-------------|
| `_id`       | string                     | caller  | The storage key, taken from the URL. Must match `^[A-Za-z0-9:_-]{1,256}$`. |
| `value`     | embedded document          | caller  | The full domain document. Storage never interprets it. |
| `indexes`   | embedded document (string → string) | caller | Flat attributes that queries search on. `collection` is required. Names may use letters, digits and `_` only (max 64 chars). |
| `version`   | int64                      | storage | Starts at 1 on create and is incremented (`$inc`) by every successful write. |
| `updatedAt` | date (UTC, millisecond precision) | storage | Time of the last successful write. Replicas store the primary's value unchanged. |
| `deleted`   | bool (absent = false)      | storage | `true` marks a tombstone: the record was deleted. `value` is `{}`. Hidden from reads and queries; kept so deletes replicate and resync like writes. |

### Key formats (spec §3.5)

| Collection | Key format                   | Example |
|------------|------------------------------|---------|
| request    | `request:{homeCell}:{uuid}`  | `request:tdr1y:f3b1c2e4-0d1e-4a5b-9c8d-7e6f5a4b3c2d` |
| resource   | `resource:{homeCell}:{uuid}` | `resource:tdr1y:8a7c3e0e-4c1d-4b9a-9d55-0d3c2f1a9b77` |

`homeCell` is the geohash (precision 5) of the entity's location **at creation**, and
never changes. The coordinator places every record on shard `crc32(homeCell) mod 3`
(ADR 0006), so a record never moves between shards. A key without a cell segment
(e.g. `request:{uuid}`) is routed on its second segment instead.

The client-facing `resourceId` is `res_{geoCell}_{uuid}`. The Resource Service
translates between that and the storage key.

### Index attributes the services are expected to write

| Attribute    | Example     | Used for |
|--------------|-------------|----------|
| `collection` | `resource`  | Required on every record; every query filters on it |
| `type`       | `WATER`     | Matching by category |
| `status`     | `AVAILABLE` | Finding available resources / pending requests |
| `geoCell`    | `tdr1yz`    | Geo search (cell + 8 neighbours) |

Storage accepts other valid attribute names too, but only the four above have MongoDB
indexes.

---

## 3. MongoDB indexes

Created at storage-node startup. Creating an index that already exists is a no-op, so
this is safe on every restart.

| Index                         | Type                | Used for |
|-------------------------------|---------------------|----------|
| `_id`                         | built-in, unique    | Get/put/delete by key; a duplicate `_id` produces the create-only `409` |
| `indexes.collection`          | single field, asc   | Every query |
| `indexes.geoCell`             | single field, asc   | `geoCells: [...]` → `$in` |
| `indexes.type`                | single field, asc   | `match.type` |
| `indexes.status`              | single field, asc   | `match.status` |
| `updatedAt, _id`              | compound, asc       | Recovery sync: page through changes in `(updatedAt, _id)` order |

---

## 4. Example documents

### Resource

```js
{
  _id: "resource:tdr1yz:8a7c3e0e-4c1d-4b9a-9d55-0d3c2f1a9b77",
  value: {
    resourceId: "res_tdr1yz_8a7c3e0e-4c1d-4b9a-9d55-0d3c2f1a9b77",
    type: "WATER",
    provider: { name: "Akshaya NGO", kind: "NGO", contact: "..." },
    location: { lat: 12.97, lng: 77.59 },
    geoCell: "tdr1yz",
    capacity: { total: 500, remaining: 480, unit: "L" },
    details: {},
    status: "AVAILABLE",
    createdAt: "2026-11-14T06:53:00Z",
    updatedAt: "2026-11-14T07:10:00Z"
  },
  indexes: { collection: "resource", type: "WATER", status: "AVAILABLE", geoCell: "tdr1yz" },
  version: NumberLong(7),
  updatedAt: ISODate("2026-11-14T07:10:00.123Z")
}
```

### Request

```js
{
  _id: "request:f3b1c2e4-0d1e-4a5b-9c8d-7e6f5a4b3c2d",
  value: {
    requestId: "f3b1c2e4-0d1e-4a5b-9c8d-7e6f5a4b3c2d",
    type: "WATER",
    location: { lat: 12.9716, lng: 77.5946 },
    geoCell: "tdr1yz",
    status: "MATCHED",
    requester: { name: "...", contact: "..." },
    details: { quantity: 20, unit: "L" },
    assignedResourceId: "res_tdr1yz_8a7c3e0e-4c1d-4b9a-9d55-0d3c2f1a9b77",
    createdAt: "2026-11-14T06:53:00Z",
    updatedAt: "2026-11-14T06:53:02Z"
  },
  indexes: { collection: "request", type: "WATER", status: "MATCHED", geoCell: "tdr1yz" },
  version: NumberLong(2),
  updatedAt: ISODate("2026-11-14T06:53:02.481Z")
}
```

The contents of `value` follow the domain model in PROJECT_SPEC §3.2 (requests) and
§3.3 (resources). The Java services write it in that shape; storage does not check it.

---

## 5. How API operations map to MongoDB

Every write is a **single atomic operation on one document**. That is what makes
optimistic concurrency (`expectedVersion`) safe.

| API call | `expectedVersion` | MongoDB operation | Result |
|----------|-------------------|-------------------|--------|
| PUT      | omitted           | `findOneAndUpdate({_id}, {$set:{value,indexes,updatedAt,deleted:false}, $inc:{version:1}}, {upsert:true, returnDocument:"before"})` | 201 if there was no live record before (new key or tombstone), else 200 |
| PUT      | `0`               | `findOneAndUpdate({_id, deleted:true}, {$set:…, $inc:{version:1}}, {upsert:true})` | 201 (new key, or tombstone revived with its version count kept), or **409** on duplicate `_id` (live record exists) |
| PUT      | `n ≥ 1`           | `findOneAndUpdate({_id, version:n, deleted:{$ne:true}}, {$set:…, $inc:{version:1}}, {returnDocument:"after"})` | 200; if nothing matched, re-read: missing → **412**, other version → **409** |
| DELETE   | omitted           | `findOneAndUpdate({_id, deleted:{$ne:true}}, {$set:{deleted:true, value:{}, updatedAt}, $inc:{version:1}})` | tombstone (coordinator answers 204), or **404** |
| DELETE   | `n ≥ 1` (query param) | same, with `version:n` in the filter | tombstone; if nothing matched, re-read: missing → **404**, other version → **409** |
| GET      | —                 | `findOne({_id, deleted:{$ne:true}})` | 200, or **404** |
| replica PUT | — (record carries its version) | `updateOne({_id, version:{$lte:v}}, {$set: record as given}, {upsert:true})` | applied; a newer stored copy makes the upsert collide on `_id` → ignored |

Notes:
- Two concurrent unconditional upserts of the same *new* key can race. MongoDB fails one
  of them with a duplicate-key error, and the store retries it once as an update.
- The re-read after a failed conditional write only chooses the error code. The write
  itself was already correctly rejected atomically.

### Query (`POST /v1/query`) → filter

```json
{ "collection": "resource", "geoCells": ["tdr1yz", "tdr1yy"],
  "match": { "type": "WATER", "status": "AVAILABLE" }, "limit": 10 }
```

becomes

```js
db.records.find({
  "indexes.collection": "resource",
  "indexes.geoCell":    { $in: ["tdr1yz", "tdr1yy"] },
  "indexes.type":       "WATER",
  "indexes.status":     "AVAILABLE"
}).sort({ _id: 1 }).limit(10)
```

- An omitted or empty `geoCells` list means "any cell".
- `limit` defaults to 50; the maximum is 1000. The list may hold at most 100 `geoCells`.
- Results are sorted by `_id`, so they come back in a deterministic order.
- `match` values are plain strings, so they are always compared for equality and can
  never act as MongoDB operators. `match` names are validated, so `$where` or `a.b`
  are rejected.

---

## 6. Conventions and caveats

- **Values appear twice.** `status`, `type` and `geoCell` live both in `value` and in
  `indexes`. Queries only look at `indexes`, so a service that changes a status must
  update both copies in the same write.
- **Number types are preserved.** `value` is converted between JSON and BSON with
  MongoDB's relaxed Extended JSON:
  - integers stay integers (`480`, not `480.0`)
  - decimals stay doubles
  - int64 values above 2^53 keep full precision
  - key order is kept

  Side effect: `$`-prefixed keys such as `$date` are read as Extended JSON type
  wrappers, so domain documents must not use `$`-prefixed keys.
- **Dates inside `value` are strings** (ISO-8601). Only the top-level `updatedAt` is a
  real BSON date.
- **Tombstones are kept forever.** Deleting and recreating a key therefore keeps
  counting versions up, so a stale `expectedVersion` from before the delete can never
  match the new record. Purging old tombstones is future work. Recovery needs them only
  for as long as a node can stay down.

---

## 7. Inspecting the data

With the stack running (`make up-dev`):

```bash
docker exec -it emergency-mongo-1-a-1 mongosh storage   # mongo-{shard}-{a|b}
```

```js
db.records.find()                                  // all records
db.records.find({ "indexes.collection": "resource" })
db.records.find({ deleted: true })                 // tombstones
db.records.getIndexes()                            // the six indexes above
```
