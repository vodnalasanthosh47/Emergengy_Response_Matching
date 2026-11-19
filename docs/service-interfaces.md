# Service Interfaces

How the Java services depend on each other and on shared code, so the person working
on one service can see what the others rely on. **Whenever you change something listed
here, add a line to the change log at the bottom in the same PR** and tell the others.

Related: `docs/data-format.md` (how records are stored), `contracts/` (the APIs and the
event), `PROJECT_SPEC.md` (the plan), and each service's README for its status and what
is left (e.g. `services/request_service/README.md`).

---

## 1. Who talks to whom

```
client ──► gateway ──/requests──► Request Service ──publish RequestCreated──► RabbitMQ ──► Matching Service
                   ──/resources─► Resource Service                                          │
                   ──/match─────► Matching Service ◄────────────────────────────────────────┘
                                                                     
Request / Resource / Matching ──/v1/data, /v1/query──► coordinator ──► storage shards
```

- **No service calls another service over HTTP.** They share data through storage and
  one event through RabbitMQ.
- **Request ↔ Resource:** they never talk directly. The link between them is
  `assignedResourceId` (a resource's ID, written into a request by the Matching
  Service), and the shared categories, units and geohash rules below.
- **Matching** reads requests (Request Service's format) and resources (Resource
  Service's format) and changes both: it claims resource capacity and marks requests
  `MATCHED` / `UNMATCHED`. So the request and resource document formats are interfaces
  between the services, not private details.

---

## 2. Shared code: the `common` module

All Java services depend on `common/` (Maven module `com.emergency:common`). It contains
contracts and small utilities only, never business logic.

| Package | What | Owner |
|---|---|---|
| `common.storage` | `StorageClient` (the only way to reach storage), `StorageKeys`, `StorageQuery`, `StorageRecord`, storage exceptions | shared |
| `common.geo` | `GeoHash` (encode, cell + 8 neighbours), `GeoDistance` (haversine km) | shared |
| `common.model` | `Category`, `Location` | shared |
| `common.api` | `ErrorResponse` (the one error JSON) and its codes | shared |
| `common.request` | `RequestDocument`, `RequestView`, `RequestStatus` (+ transition table), `Requester` | Request Service |
| `common.events` | `RequestCreatedEvent`, `EventTopology` (exchange, routing keys, queue names) | Request Service (publisher) + Matching (consumer) |
| `common.resource` | `ResourceDocument`, `ResourceView`, `ResourceStatus` (+ transition table), `Capacity` (+ unit per type), `Provider`, `ProviderKind` | Resource Service |

Rules:
- Add new packages for a new service's types (e.g. `common.resource`) rather than
  editing another service's package. That keeps parallel branches from conflicting.
- Changing a "shared" class changes every service: add a change-log entry.

### Using the storage client

```java
StorageClient storage = new StorageClient(coordinatorUrl, Duration.ofSeconds(10), jsonMapper);

String key = StorageKeys.resource(cell, uuid);                    // resource:{cell}:{uuid}
StorageRecord r = storage.create(key, document, indexes);          // must not exist (expectedVersion 0)
Optional<StorageRecord> found = storage.get(key);                  // empty if missing
MyDoc doc = storage.valueAs(found.get(), MyDoc.class);
storage.replace(key, changed, indexes, found.get().version());     // compare-and-set
List<StorageRecord> near = storage.query(new StorageQuery("resource",
        GeoHash.withNeighbours(cell), Map.of("type", "WATER", "status", "AVAILABLE"), 50));
```

| Exception | Coordinator | What your service does |
|---|---|---|
| `VersionConflictException` | 409 | re-read, decide again |
| `PreconditionFailedException` | 412 | the record is gone → 404 |
| `RecordNotFoundException` | 404 on delete | already gone |
| `InvalidStorageRequestException` | 400 | a bug in your service (key/indexes format) → 500 |
| `StorageUnavailableException` | 5xx, unreachable, timeout | answer 503 |

Writes are always conditional; there is no unconditional write on purpose. Stored
documents are read **tolerantly** (unknown fields ignored), so adding a field to a
document never breaks another service that reads it. API input stays strict.

---

## 3. Agreements every service must follow

| Topic | Agreement | Where |
|---|---|---|
| Categories | `WATER`, `FOOD`, `MEDICINE`, `TRANSPORT`, `SHELTER` (`common.model.Category`); upper case on the wire | contracts/openapi/common.yaml |
| Geohash | `GeoHash.encode(lat, lng, GEOHASH_PRECISION)`, precision **6** everywhere. Real cell of 12.9716, 77.5946 is `tdr1v9` (the spec's `tdr1yz` is wrong) | docs/data-format.md §2 |
| Storage keys | `request:{requestId}`, `resource:{geoCell}:{uuid}`; use `StorageKeys` | docs/data-format.md §2 |
| Resource IDs | clients see `res_{geoCell}_{uuid}`; `StorageKeys.resourceId` / `resourceIdToKey` | docs/data-format.md §2.2 |
| Indexes | `collection`, `type`, `status`, `geoCell`; resource `geoCell` = key cell; keep `status` in sync with the value in the same write | docs/data-format.md §4 |
| Versions | never stored inside the document; API responses add `version` from the record | docs/data-format.md §3.2 |
| Errors | `{error, message, timestamp}`; codes `INVALID_REQUEST` 400, `NOT_FOUND` 404, `CONFLICT` 409, `NO_MATCH` 422, `SERVICE_UNAVAILABLE` 503, `INTERNAL_ERROR` 500 (`common.api.ErrorResponse`) | contracts/openapi/common.yaml |
| Unknown JSON fields | rejected (400) in API bodies: `spring.jackson.deserialization.fail-on-unknown-properties: true` | each service's application.yml |
| Numbers as text | rejected (400): `"500"` is not a number. `common.api.JsonStrictness`, applied by each service's `config/JacksonConfig` (controller tests must `@Import` it) | common, each service |
| Config | env vars only: `SERVER_PORT`, `COORDINATOR_URL`, `RABBITMQ_*`, `GEOHASH_PRECISION` | spec §6 |
| Health | `/actuator/health/liveness`, `/actuator/health/readiness` (include `storage`), `/actuator/prometheus` | request service's application.yml |

### Amounts and units (request `details` ↔ resource `capacity`) — agreed

Matching compares a request's amount with a resource's `capacity.remaining`, so they
must be in the same unit. Both services implement this table (the resource side in
`Capacity.unitFor` and `ResourceValidator`):

| type | request amount (Request Service) | resource `capacity.unit` (Resource Service) | extra rule |
|---|---|---|---|
| WATER | `details.quantity` litres (decimal allowed), `unit: "L"` | `L` | |
| FOOD | `details.quantity` meals (whole), `unit: "MEALS"` | `MEALS` | |
| MEDICINE | `details.quantity` (whole) | `UNITS` | `details.name` must equal the resource's `details.name` (compare trimmed, case-insensitive) |
| TRANSPORT | `details.people` (whole) | `PEOPLE` (seats) | |
| SHELTER | `details.people` (whole) | `PEOPLE` (beds) | |

---

## 4. What the Request Service provides

| Interface | Details |
|---|---|
| Public API | `POST /requests`, `GET /requests/{id}`, `PUT /requests/{id}/status` (contracts/openapi/request-service-api.yaml) |
| Stored request | `request:{requestId}` → `RequestDocument` (docs/data-format.md §5) |
| Event | `RequestCreated` v1 on `emergency.events`, routing key `request.created.<type>`, persistent, publisher-confirmed, `message-id` = `eventId` (contracts/events/request-created.v1.json) |
| Status rules | `RequestStatus.canTransitionTo`; clients may set `PENDING` (from UNMATCHED), `IN_PROGRESS`, `FULFILLED`, `CANCELLED`; **`MATCHED` / `UNMATCHED` only by the Matching Service** |

What the Matching Service must do with requests (spec §5): re-read the request before
acting (the event may be stale); act only if `status.isMatchable()`; write `MATCHED` +
`assignedResourceId` (and `indexes.status`) with `expectedVersion`; on 409 re-read.

Known gaps (documented, not solved in CP1):
- A crash between storing and publishing leaves a PENDING request without an event
  (if publishing fails without a crash, the request is deleted again and the client gets 503).
- If no queue is bound yet (Matching Service never started), events are not routed;
  the Request Service logs a warning. Start the Matching Service before creating requests,
  or use `POST /match/{id}` later.

---

## 5. What the Resource Service provides

| Interface | Details |
|---|---|
| Public API | `POST /resources`, `GET /resources?type=&status=&limit=`, `GET /resources/{id}`, `PUT /resources/{id}/status` (contracts/openapi/resource-service-api.yaml) |
| Stored resource | `resource:{geoCell}:{uuid}` → `ResourceDocument` (docs/data-format.md §6); `geoCell` from `GeoHash.encode(lat, lng, 6)`, the same as requests |
| Capacity | `capacity.total` / `remaining` are numbers in the unit above; `remaining` starts at `total` unless the provider says otherwise |
| Status rules | `ResourceStatus.canTransitionTo`; clients may set `AVAILABLE` and `OFFLINE`; **`DEPLETED` (and `DEPLETED → AVAILABLE`) only by the Matching Service**, in the write that changes `capacity.remaining` |

What the Matching Service must do with resources (spec §5): query candidates with
`StorageQuery("resource", GeoHash.withNeighbours(cell), {type, status: AVAILABLE}, n)`;
claim by re-reading the resource and writing `capacity.remaining - amount` with
`expectedVersion`; at `0` also set `status: DEPLETED` in both `value` and `indexes`;
on `409` re-read and check again.

---

## 5a. What the Matching Service provides and relies on

| Interface | Details |
|---|---|
| Public API | `POST /match/{requestId}`: 200 assignment, 422 `NO_MATCH`, 409 not matchable, 404, 400 (contracts/openapi/matching-service-api.yaml) |
| Consumes | `RequestCreated` v1 from queue `matching.request-created` (bound `request.created.*`); dead letters to `matching.request-created.dlq` via `emergency.dlx` (fanout). Declared by this service on startup |
| Writes requests | only `MATCHED` (+ `assignedResourceId`) and `UNMATCHED`, with `expectedVersion`, status index in sync (`RequestDocument.matchedTo`) |
| Writes resources | claims and releases: `capacity.remaining` and `status` (`DEPLETED` at 0, back to `AVAILABLE` on release) together, with `expectedVersion` (`ResourceDocument.withCapacity`) |
| Relies on | the request `details` amount fields and the resource `capacity.unit` table in §3; `RequestStatus.isMatchable`; resources' `geoCell` = key cell (geo-routed search) |

A new field added to a request or resource document is ignored by matching (stored
documents are read tolerantly), but **renaming or removing** `details.quantity`,
`details.people`, `details.name`, `capacity.*`, `status` or `location` breaks it: add a
change-log line and tell the matching owner.

---

## 6. Adding the Resource Service (checklist that branch followed)

1. Branch from `main` **after** the request-service PR is merged (it brings `common`, the
   root `pom.xml` and the Compose/gateway wiring). Until then, branch from
   `feat/request-service`.
2. `services/resource_service/`: copy the request service's `pom.xml` (artifactId
   `resource-service`), `Dockerfile` (replace `request_service`/`request-service`), and
   `application.yml` (name, settings). Package `com.emergency.resource.{api,domain,storage,config}`.
3. Root `pom.xml`: add `<module>services/resource_service</module>`. Makefile: add
   `resource_service` to `JAVA_SERVICES`.
4. `contracts/openapi/resource-service-api.yaml`, referencing `./common.yaml` for
   `Category`, `Location`, `GeoCell`, `Error` and the shared responses.
5. `common.resource`: `ResourceDocument` (docs/data-format.md §6), so Matching can read it.
6. Compose: a `resource-service` block like `request-service` (network `services`);
   `deploy/compose/nginx.conf`: a `location ~ ^/resources(/|$)` block like `/requests`.
7. Keys/indexes: `StorageKeys.resource(GeoHash.encode(lat, lng, 6), uuid)`, indexes
   `{collection: resource, type, status, geoCell}` with `geoCell` = the key's cell.
8. Tests like the request service's: domain with fakes, `@WebMvcTest`, storage adapter
   with `MockRestServiceServer`, a `tests/e2e/resource-smoke.sh`.

Files both branches touch (expect small, mechanical merge conflicts; keep both sides):
root `pom.xml` `<modules>`, `Makefile` `JAVA_SERVICES`, `deploy/compose/docker-compose.yml`,
`deploy/compose/nginx.conf`, `README.md`.

---

## 7. Change log

Newest first. Anything that changes an interface above gets a line.

| Date | Who | Change | Affects |
|---|---|---|---|
| 2026-11-19 | Matching branch | Matching Service added: `POST /match/{id}`, queue `matching.request-created` + DLQ, `/match` gateway route; `common`: `RequestDocument.matchedTo`, `ResourceDocument.withCapacity` | everyone |
| 2026-11-19 | Matching branch | With matching running, new requests are matched (or UNMATCHED) within about a second: tests must not assume a request stays PENDING (`request-smoke` asks for an unmatchable amount) | everyone writing e2e tests |
| 2026-11-19 | Matching branch | `make up` reloads the gateway's nginx config (a running gateway did not see new routes); `make smoke` runs all service smoke tests | everyone |
| 2026-11-19 | Request branch | Numbers sent as text (`"500"`, `"12.97"`) are now 400 in every service (`common.api.JsonStrictness` + `config/JacksonConfig` in each service; a new service must add the same config) | everyone |
| 2026-11-19 | Request branch | Resource capacity amounts: at most 1,000,000 and 3 decimal places (`1e400` was a 500); `1e3` stored as `1000` | Resource, Matching |
| 2026-11-19 | Request branch | Storage e2e scripts now delete the raw test records they create (they used to appear in `GET /resources` and broke `resource-smoke` on a shared stack) | everyone running e2e tests |
| 2026-11-19 | Resource branch | Added `common.resource` (`ResourceDocument`, `ResourceView`, `ResourceStatus`, `Capacity`, `Provider`, `ProviderKind`), the Resource API (contracts/openapi/resource-service-api.yaml), and the `/resources` gateway route | Matching |
| 2026-11-19 | Resource branch | Resource `capacity.unit` per type agreed (L, MEALS, UNITS, PEOPLE) and implemented | Request, Matching |
| 2026-11-19 | Request branch | Added `common` (storage client, keys, geohash, categories, error JSON, request types, `RequestCreated`), root Maven build, RabbitMQ + gateway in Compose | everyone |
| 2026-11-19 | Request branch | Request API, stored request format and `RequestCreated` v1 defined (contracts/) | Matching |
| 2026-11-19 | Request branch | Proposed resource `capacity.unit` per type (§3); needs Resource Service agreement | Resource, Matching |
