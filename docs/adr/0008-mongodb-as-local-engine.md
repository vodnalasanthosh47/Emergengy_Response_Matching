# ADR 0008 — MongoDB as each storage node's local engine; sharding and replication are ours

- **Status:** Accepted (CP1)
- **Spec reference:** PROJECT_SPEC §2.2-8

## Context

Each storage node must store records durably, look them up by key, answer simple
index queries (collection, geo cell, type, status), and apply conditional writes
atomically. Writing our own durable storage engine is out of scope. The course grades
the **distributed** parts (routing, replication, failover, recovery), so those must be
our own code, not a database feature.

## Decision

Each storage-node pod runs a **standalone** `mongod`:

- In CP1 it is a separate Compose container on an internal network.
- From CP2 it is a sidecar in the same pod, backed by a PVC.

Only the storage-node process talks to it.

- **Do not use Mongo's distributed features.** MongoDB replica sets and sharded clusters
  are **not** used.
- **Document shape:** one collection `records` holding
  `{ _id: key, value, indexes, version, updatedAt }`, with indexes on `indexes.collection`,
  `indexes.geoCell`, `indexes.type` and `indexes.status`.
- **Conditional writes** are single-document atomic operations: `findOneAndUpdate`
  filtered on `{ _id, version: expectedVersion }`, and `insertOne` for "must not exist".
- **Value conversion:** `value` is converted between JSON and BSON with Mongo's relaxed
  Extended JSON, so integers stay integers. A plain `map[string]any` would turn `480`
  into `480.0`.
- **The HTTP contracts stay database-agnostic.** Nothing outside `storage_node/internal/store`
  knows MongoDB exists.

## Consequences

- **Good:**
  - Durability, indexing and atomic single-document updates come from a mature engine.
  - The distributed algorithms remain ours to build and explain.
  - Swapping the engine later (e.g. for SQLite or bbolt) touches one package.
- **Bad:** an extra container per node (≈512 MB memory limit; WiredTiger cache capped
  at 0.25 GB to fit a laptop).
- **Rule for the team:** never "fix" a distributed-storage problem by turning on a Mongo
  replica set. That would replace exactly the part the course grades.
