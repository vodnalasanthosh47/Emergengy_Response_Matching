# ADR 0006 — Sharding: requests by request ID, resources by geohash cell

- **Status:** Accepted (revised 2026-11-19: requests are no longer placed by cell)
- **Spec reference:** PDF §4 ("hash(request_id) mod 3"; "the client should not need to know the physical storage location"), §12 ("exact sharding and partitioning strategy"); PROJECT_SPEC §2.2-6, §3.5

## Context

Requests and resources must be spread over 3 shards, and the coordinator must find a
record's shard from its key alone (a `GET` by ID carries nothing else). The two
collections are used differently:

- **Requests** are written once per emergency and then read and updated **by ID**
  (`GET /requests/{id}`, status changes, the matcher re-reading it). They are never
  searched by location at the storage level.
- **Resources** are searched **by location**: "available WATER near cell C" means the
  request's geohash cell plus its 8 neighbours.

A disaster concentrates requests in a few cells, so placing requests by location
would put most of the load on one shard.

## Decision

| Collection | Key | Shard |
|---|---|---|
| request | `request:{requestId}` | `crc32(requestId) mod 3` |
| resource | `resource:{geoCell}:{uuid}` | `crc32(geoCell) mod 3` |
| any other | any valid key | `crc32(key) mod 3` |

- **Requests by ID** (the PDF's `hash(request_id) mod 3`): an even spread whatever
  the location, and a lookup by ID touches exactly one shard.
- **Resources by geohash cell:** every resource of a cell lives on one shard. A
  proximity query over 9 cells is sent **only to the shards that own those cells**
  (`store.TargetShards`): at most 3, often fewer, and it keeps working while an
  unrelated shard is down.
- `geoCell` is the geohash of the resource's location at the precision the Resource
  Service uses (`GEOHASH_PRECISION`, default 6 ≈ 1.2 × 0.6 km; spec §3.4). The
  coordinator does not fix the precision, but it must not change once data exists.
- **The cell is part of the key, so it never changes.** Moving a resource means
  re-keying it (delete + create), as spec §3.5 already accepts.
- **Keys and indexes must agree.** The coordinator rejects (`400`) a request key that
  is not `request:{id}`, a resource key whose cell is not a lower-case geohash, a
  record whose `indexes.collection` differs from its key's collection, and a resource
  whose `indexes.geoCell` differs from its key's cell. Otherwise a resource could be
  stored on one shard but searched for on another, and silently never matched.
- **Stable hash:** CRC32, not a language's per-process randomised hash, so routing is
  identical across restarts, coordinator instances and languages. The tests pin
  values computed independently with Python's `zlib.crc32`.

The exact key and record format the services must write is in
[`docs/data-format.md`](../data-format.md).

## Consequences

- **Good:**
  - Single-shard key lookups for both collections.
  - Requests spread evenly even when every request comes from the same cell.
  - Nearby-resource searches touch only the shards that own the searched cells.
  - The client never sees shards; the Java services only build keys.
- **Bad:**
  - A dense area's resources sit on one shard (geo hotspot). Accepted at course scale.
    A finer precision spreads them more, at the cost of more cells per search.
  - Listing queries without cells (`GET /resources`, `GET /requests`) still go to all
    3 shards.
  - The Java services must build keys exactly as above (the coordinator enforces it).
- **Changing the shard count** would re-home keys. It needs a migration, or
  consistent hashing (future work).
