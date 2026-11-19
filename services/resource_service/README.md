# Resource Service

The `/resources` API of the Emergency Resource Matching system (PROJECT_SPEC §4.1,
§7.1-D; PDF §5). It registers resources offered by volunteers, NGOs, depots or
individuals, lists them, and changes their status. All data goes through the storage
coordinator; the service never talks to a storage node or MongoDB.

**Status (2026-11-19): done for Checkpoint 1.** Unit, web-layer and application tests pass,
and `make resource-smoke` passes against the real storage cluster in Docker Compose.
Branch `feat/resource-service`.

Related documents:
- [`contracts/openapi/resource-service-api.yaml`](../../contracts/openapi/resource-service-api.yaml): the API
- [`docs/data-format.md`](../../docs/data-format.md) §6: how a resource is stored
- [`docs/service-interfaces.md`](../../docs/service-interfaces.md): what the other services rely on

---

## 1. Endpoints

| Endpoint | Success | Errors |
|---|---|---|
| `POST /resources` | `201` + `Location: /resources/{id}` + the resource | `400`, `503` |
| `GET /resources?type=&status=&limit=` | `200` `{"resources": [...]}` | `400` bad filter, `503` |
| `GET /resources/{id}` | `200` the resource | `400` malformed ID, `404`, `503` |
| `PUT /resources/{id}/status` | `200` the updated resource | `400`, `404`, `409`, `503` |

Errors use the shared JSON: `{"error": "...", "message": "...", "timestamp": "..."}`.

### Registering

```bash
curl -X POST http://localhost:8080/resources -H 'Content-Type: application/json' -d '{
  "type": "WATER", "location": {"lat": 12.9716, "lng": 77.5946},
  "provider": {"name": "Akshaya NGO", "kind": "NGO", "contact": "+91-80000-00000"},
  "capacity": {"total": 500} }'
```

The server:
1. checks the generic fields (Bean Validation), then the per-type capacity and details;
2. computes `geoCell = GeoHash.encode(lat, lng, GEOHASH_PRECISION)` with the shared
   `common.geo.GeoHash`, the same code and precision as the Request Service, so a request
   and a nearby resource fall in the same or neighbouring cells (`tdr1v9` here);
3. builds `resourceId = res_{geoCell}_{uuid}`, sets `status: AVAILABLE`, and sets
   `capacity.remaining = capacity.total` unless the client sent a remaining amount;
4. stores it create-only under `resource:{geoCell}:{uuid}`.

Clients may not send `resourceId`, `geoCell` or `status` (unknown fields are `400`).

| type | `capacity.unit` | amounts | `details` |
|---|---|---|---|
| WATER | `L` | decimal > 0, at most 3 decimal places | `{}` |
| FOOD | `MEALS` | whole > 0 | `{}` |
| MEDICINE | `UNITS` | whole > 0 | `{"name": text}`, required |
| TRANSPORT | `PEOPLE` | whole > 0 | `{}` |
| SHELTER | `PEOPLE` | whole > 0 | `{}` |

The unit may be omitted (it is filled in); `remaining` must be `> 0` and `≤ total`.
Every amount is at most 1,000,000 and must be a JSON number: `"500"` as text is `400`
(storage keeps numbers as 64-bit floats, so e.g. `1e400` used to fail with a `500`).
`1e3` is stored as `1000`.
These are the units the Request Service uses for the amount it asks for, so matching
can compare a request's amount with `capacity.remaining` directly.

### Status changes

| from | to |
|---|---|
| AVAILABLE | DEPLETED*, OFFLINE |
| DEPLETED | AVAILABLE* (capacity released), OFFLINE |
| OFFLINE | AVAILABLE |

- Clients may set `AVAILABLE` and `OFFLINE`. `DEPLETED` (and leaving it for
  `AVAILABLE`) is done by the Matching Service in the same write that changes the
  capacity, so requesting `DEPLETED` here is `400`.
- `AVAILABLE` with no remaining capacity is `409`.
- Setting the current status again is a no-op `200`.
- Read → check → write with `expectedVersion`; on a storage `409` (e.g. a claim landed in
  between) it re-reads and decides again, up to 3 times.

The transition table is `common.resource.ResourceStatus`, shared with matching.

---

## 2. Stored data

```
key      resource:{geoCell}:{uuid}                     shard = crc32(geoCell) mod 3
value    ResourceDocument: resourceId, type, provider, location, geoCell, capacity,
         details, status, createdAt, updatedAt
indexes  collection=resource, type, status, geoCell   (status updated in the same write)
version  managed by storage; never stored in the value; shown in API responses
```

`capacity.total` and `capacity.remaining` are `BigDecimal`, so `500` is stored as `500`
(not `500.0`) and claims subtract exactly.

---

## 3. Code layout (Spring MVC: controller → service → repository)

```
services/resource_service/src/main/java/com/emergency/resource/
  controller/  ResourceController (HTTP only), GlobalExceptionHandler (error JSON)
  dto/         RegisterResourceRequest, StatusUpdateRequest, ResourceListResponse
  service/     ResourceService (rules), ResourceValidator (per-type capacity/details), NewResource
  repository/  ResourceRepository, CoordinatorResourceRepository (StorageClient), StoredResource
  exception/   InvalidResource (400), ResourceNotFound (404), ResourceConflict (409),
               DependencyUnavailable (503), ConcurrentUpdate (internal retry)
  config/      AppConfig (StorageClient, Clock), ResourceServiceProperties, StorageHealthIndicator
common/src/main/java/com/emergency/common/resource/
               ResourceDocument, ResourceView, ResourceStatus, Capacity, Provider, ProviderKind
```

Reused from `common`: `StorageClient`, `StorageKeys`, `StorageQuery`, `GeoHash`,
`Category`, `Location`, `ErrorResponse`.

---

## 4. Configuration

| Variable | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `COORDINATOR_URL` | `http://localhost:9090` | storage coordinator |
| `GEOHASH_PRECISION` | `6` | must equal the Request Service's |
| `STORAGE_TIMEOUT` | `10s` | bound on each storage call |

Operations: `/actuator/health/liveness`, `/actuator/health/readiness` (needs the
coordinator), `/actuator/prometheus`.

---

## 5. Running and testing

```bash
make images && make up-dev
make resource-smoke     # end to end through the gateway
make test-java          # all Java tests
```

| Suite | What it proves |
|---|---|
| `ResourceValidatorTest` | units, defaults, whole vs decimal amounts, maximum and decimal places, `1e3` stored as `1000`, MEDICINE name, unknown details |
| `ResourceServiceTest` | ID/cell/status generation, list filters and limit, every client transition, no-op, reserved DEPLETED, empty → AVAILABLE refused, conflict retry, storage down |
| `CoordinatorResourceRepositoryTest` | exact key, value, indexes, `expectedVersion` and query body sent; storage outcomes → service exceptions |
| `ResourceControllerTest` | status codes and error JSON for bad bodies (including numbers sent as text), bad query parameters, 404/405/415/500 |
| `ResourceServiceApplicationTest` | whole app over HTTP against a fake coordinator; 503 and not ready when storage is down |
| `common`: `ResourceJsonTest`, `ResourceStatusTest` | stored JSON shape (integers stay integers), transition table |

`make resource-smoke` also checks that resources in cells `tdr1v2`, `tdr1v9`, `tdr1vf` land
on shards 0, 1, 2. Checked by hand: killing the process (`kill 1`) → the gateway answers
the JSON 503 → Docker restarts it.

---

## 6. What's left

| Item | Notes |
|---|---|
| Matching Service claims capacity | Read the resource, subtract with `expectedVersion`, set `DEPLETED` at 0 (value and index); see `docs/data-format.md` §7 |
| Restocking / changing capacity or location | Not in the spec's API. Moving a resource means re-keying it (new cell → new ID) |
| Kubernetes Deployment, Service, HTTPRoute | CP2, with the other services |
