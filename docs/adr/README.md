# Architecture Decision Records

One ADR per key decision in PROJECT_SPEC §2.2. Each records the context, the decision
and its consequences, so either team member can explain *why* in the viva.

| # | Decision | Status |
|---|---|---|
| 0001 | Three stateless application services + separate stateful storage tier | to write |
| [0002](0002-coordinator-as-storage-proxy.md) | Coordinator is a proxy for all data operations | Accepted |
| 0003 | Gateway API (not ingress-nginx) for client routing | to write |
| 0004 | No double-booking: optimistic concurrency with `version` / `expectedVersion` | to write |
| 0005 | Per-operation consistency (accept = availability, claim = consistency) | to write |
| [0006](0006-sharding-by-geohash.md) | Sharding: requests by `crc32(requestId)`, resources by `crc32(geoCell)`, mod 3 | Accepted |
| 0007 | No Redis / caching | to write |
| [0008](0008-mongodb-as-local-engine.md) | MongoDB as each node's local engine; sharding and replication are ours | Accepted |
| [0009](0009-replication-failover-recovery.md) | Primary/replica per shard; synchronous replication, failover and recovery via the coordinator | Accepted |
