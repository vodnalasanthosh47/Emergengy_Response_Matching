# ADR 0002 — The coordinator is a proxy for all data operations

- **Status:** Accepted (CP1)
- **Spec reference:** PROJECT_SPEC §2.2-2, §4.2

## Context

The application services (Request, Resource, Matching) need to read and write records.
From CP2 the data is sharded across three storage nodes, and from CP3 each shard has a
replica that takes over on failure. We have two options:

1. Services find the right storage node themselves: a "smart client" that knows the ring
   and the node health.
2. Services call one coordinator, and it routes each call to the right node.

## Decision

Services talk **only** to the coordinator, through a small database-agnostic HTTP API
(`/v1/data/{key}`, `/v1/query`). The coordinator forwards each call to the storage node
that owns the key. This API is frozen from CP1.

Inside the coordinator, the HTTP handlers depend on a `RecordStore` interface:

- CP1 implementation: `Forwarder`, which sent everything to one node.
- Now: `ShardedStore`, which does sharding (requests by ID, resources by geohash; ADR 0006), replication, read
  fallback and scatter-gather queries behind the same interface (ADR 0009). The
  `Forwarder` remains as the HTTP client for one node. The `/v1` endpoints and
  their semantics did not change; the key shapes the shard is derived from are now
  enforced (ADR 0006).

The coordinator does **not** do versioning. The atomic compare-and-set (`expectedVersion`)
runs on the storage node that owns the key, so exactly one place decides each write.

## Consequences

- **Good:**
  - Routing, membership and failover logic exists once, in Go, not in every Java service.
  - Services stay simple and never need to change when the storage topology changes.
  - The CP1 → CP2 change is confined to `internal/store` and one line in `main.go`.
- **Bad:**
  - Every data call takes an extra network hop.
  - The coordinator is a single point of failure. It runs as 1 replica and holds only
    soft state, so Kubernetes restarts it quickly. Running more replicas is future work.
- **Accepted risk:** a coordinator outage makes storage unavailable, and services answer
  `503`. The course grades storage-node failover, not coordinator high availability.
