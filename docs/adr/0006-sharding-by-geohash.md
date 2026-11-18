# ADR 0006 — Sharding: by the geohash `homeCell` in the key

- **Status:** Accepted
- **Spec reference:** PDF §4 ("hash(request_id) mod 3"; "the client should not need to know the physical storage location"), §12 ("exact sharding and partitioning strategy")

## Context

Requests and resources must be spread over 3 shards, and the coordinator must find a
record's shard from its key alone. The PDF's example is `hash(request_id) mod 3`. Most
lookups are by key, but matching asks "which resources of type T are near cell C?".

## Decision

- **Key format:** `{collection}:{homeCell}:{uuid}`. `homeCell` is the geohash
  (precision 5, ≈ 4.9 × 4.9 km) of the entity's location at creation.
- **Shard:** `crc32(homeCell) mod 3` (`storage/coordinator/internal/routing`). This is
  the PDF's hash-mod-3 scheme, with the cell as the hashed part of the id.
- **`homeCell` is immutable.** A record never changes shard, and references between
  records stay valid. An entity's *current* location goes in the separate, mutable
  `indexes.geoCell` attribute.
- **Stable hash:** CRC32, not a language's per-process randomised hash, so routing is
  the same across restarts and across languages.
- **Short keys:** keys without a cell segment route on their second segment, so routing
  is defined for every key.

## Consequences

- **Good:**
  - Single-shard key lookups.
  - Everything created in one cell lives on one shard (per-region locality).
  - The client never sees shards.
- **Bad:**
  - Neighbouring cells usually map to different shards, so proximity queries are
    scatter-gather across all 3 shards.
  - A disaster concentrated in one or two cells puts most of its traffic on one shard
    (hotspot). This is accepted for the course scale. A finer precision spreads load at
    the cost of more cells per query.
- **Changing the shard count:** this would re-home keys. It needs a migration, or
  consistent hashing (future work).
