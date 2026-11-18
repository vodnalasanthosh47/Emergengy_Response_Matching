# ADR 0009 — Primary/replica per shard, coordinator-driven replication, failover and recovery

- **Status:** Accepted
- **Spec reference:** PDF §2 (≥1 replica per shard), §8 (Failure Handling), §12 ("replica synchronization mechanism")

## Context

Each shard needs at least one replica. When a storage node fails, its data must stay
available, and when it comes back it must catch up. We implement this ourselves (ADR
0008). Two writers must still never both win a claim (`expectedVersion`).

## Decision

- **Topology:** 2 nodes per shard. The coordinator's registry assigns roles: one
  PRIMARY, the rest REPLICA. Pod ordinals and container names do not decide roles.
- **Membership:**
  - Nodes register on startup and heartbeat every 5 s.
  - 3 missed heartbeats (15 s), or a failed call, mark a node DOWN.
  - DOWN nodes get no traffic and must re-register (a heartbeat gets `404`).
- **Writes are synchronous primary → replicas, through the coordinator:**
  1. The primary alone evaluates `expectedVersion` (one place decides each claim).
  2. The coordinator copies the primary's resulting record (same version and timestamp)
     to every ACTIVE and SYNCING replica.
  3. Only then does it acknowledge the write.
  - A replica that fails the copy is marked DOWN.
  - Replicas apply a copy only if its version is ≥ the version they hold. That makes
    replays and out-of-order delivery harmless.
- **Reads:** the primary, falling back to an ACTIVE replica in the same call.
- **Failover:** when a primary goes DOWN, an ACTIVE replica is promoted (newest data
  breaks ties). Every ACTIVE replica holds every acknowledged write, so none is lost.
- **Election:** a shard with no primary waits `ELECTION_WINDOW` (10 s) and elects the
  registered node with the newest `updatedAt`. This covers cold start, a coordinator
  restart, and a whole shard having been down.
- **Recovery:** a node rejoining a shard that has a primary is SYNCING. It receives new
  writes but serves no reads, and can't be promoted. The coordinator then:
  1. copies everything the primary changed since the node's newest write minus 30 s;
  2. repairs keys the node changed in that window but the primary doesn't have in the
     same version (writes it accepted but never acknowledged), conditional on the
     node's version;
  3. marks the node ACTIVE.
- **Deletes are tombstones,** so they take part in all of the above.

## Consequences

- **Good:**
  - No acknowledged write is lost on a single node failure.
  - Claims stay linearizable per key.
  - Recovery is incremental (a restarted pod keeps its volume).
  - All the logic is our own code and visible in `/internal/topology` and the logs.
- **Bad:**
  - A write waits for the slowest live replica.
  - A dead replica costs one failed write (then it is out of the set).
  - A write whose primary died mid-call returns `503`, and the client retries.
  - With every live replica of a shard still SYNCING, losing the primary makes the
    shard unavailable (`503`) until a complete copy returns. An incomplete copy is
    never promoted.
- **Accepted risk:** the coordinator is a single replica with soft state (ADR 0002).
  After it restarts, each shard is unavailable for about one election window.
