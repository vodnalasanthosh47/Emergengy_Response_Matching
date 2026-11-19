# Request Service — status and handover

The `/requests` API of the Emergency Resource Matching system (PROJECT_SPEC §4.1,
§7.1-C; PDF §5 and §7). It accepts emergency requests, stores them through the storage
coordinator, and announces each new request to the Matching Service over RabbitMQ.

**Status (2026-11-19): done for Checkpoint 1.** All tests pass (93 service + 47 `common` + all Go), and
it runs end to end in Docker Compose against the real storage cluster and RabbitMQ.
Branch `feat/request-service` (pushed, not merged into `main` yet). What is left is in
[§9](#9-whats-left).

Related documents:
- [`docs/service-interfaces.md`](../../docs/service-interfaces.md): what the services rely on from each other, plus the change log
- [`docs/data-format.md`](../../docs/data-format.md) §5: how a request is stored
- [`contracts/openapi/request-service-api.yaml`](../../contracts/openapi/request-service-api.yaml): the API
- [`contracts/events/request-created.v1.json`](../../contracts/events/request-created.v1.json): the event

---

## 1. What it does

```
client ──► gateway :8080 ──/requests──► Request Service ──/v1/data──► coordinator ──► storage shards
                                              │
                                              └──publish RequestCreated──► RabbitMQ ──► Matching Service
```

| Endpoint | Success | Errors |
|---|---|---|
| `POST /requests` | `201` + `Location: /requests/{id}` + the request | `400` invalid input, `503` storage/RabbitMQ down |
| `GET /requests/{id}` | `200` the request | `400` id is not a UUID, `404` unknown, `503` |
| `PUT /requests/{id}/status` | `200` the updated request | `400` bad/reserved status, `404`, `409` illegal transition, `503` |

Every error is `{"error": "...", "message": "...", "timestamp": "..."}` with codes
`INVALID_REQUEST`, `NOT_FOUND`, `CONFLICT`, `SERVICE_UNAVAILABLE`, `INTERNAL_ERROR`.

### Creating a request (`POST /requests`)

```bash
curl -X POST http://localhost:8080/requests -H 'Content-Type: application/json' \
  -d '{"type":"WATER","location":{"lat":12.9716,"lng":77.5946},"details":{"quantity":20},
       "requester":{"name":"Ravi Kumar","contact":"+91-90000-00000"}}'
```

1. Validates `type`, `location` (lat −90..90, lng −180..180) and `details` for the
   type. Numbers must be JSON numbers (`"12.97"` as text is rejected, as in every
   service). Unknown fields are rejected, including `geoCell`, `status` and `requestId`,
   which only the server sets.
2. Assigns a random UUID, computes `geoCell` = geohash of the location at precision 6
   (`tdr1v9` for the example), sets `status: PENDING`, `createdAt`/`updatedAt`.
3. Stores it under `request:{requestId}` with create-only (`expectedVersion: 0`).
4. Publishes `RequestCreated` and waits for RabbitMQ's confirm.
5. If publishing fails, deletes the stored request again and answers `503`, so no
   request is ever left PENDING without an event, and the client can simply retry.

### `details` per type

| type | details | normalisation |
|---|---|---|
| WATER | `{"quantity": number > 0, "unit"?: "L"}` | unit added if missing |
| FOOD | `{"quantity": whole > 0, "unit"?: "MEALS"}` | unit added if missing |
| MEDICINE | `{"name": text, "quantity": whole > 0}` | name trimmed |
| TRANSPORT | `{"people": whole > 0, "start"?: {lat,lng}, "destination"?: {lat,lng}}` | |
| SHELTER | `{"people": whole > 0}` | |

### Status changes (`PUT /requests/{id}/status`)

```
PENDING ──► MATCHED* ──► IN_PROGRESS ──► FULFILLED
   │  ▲          │
   │  └ UNMATCHED*
   └──► CANCELLED   (from PENDING, UNMATCHED, MATCHED)
```

- Clients may set `PENDING` (from UNMATCHED), `IN_PROGRESS`, `FULFILLED`, `CANCELLED`.
- \* `MATCHED` and `UNMATCHED` are reserved for the Matching Service (`400` here),
  because MATCHED must record the claimed resource in the same write.
- Setting the status a request already has is a no-op `200`, so retrying after a
  `503` is safe.
- Read → check → write with `expectedVersion`. If someone else changed the request in
  between (storage `409`), it re-reads and decides again, up to 3 times.

---

## 2. Stored data and the event

Stored record (format in `docs/data-format.md` §5):

```
key      request:{requestId}                         shard = crc32(requestId) mod 3
value    RequestDocument: requestId, type, location, geoCell, status, requester,
         details, assignedResourceId (null until MATCHED), createdAt, updatedAt
indexes  collection=request, type, status, geoCell   (status updated in the same write)
version  managed by storage; never stored inside the value; shown in API responses
```

Event `RequestCreated` v1:

| Property | Value |
|---|---|
| Exchange | `emergency.events` (topic, durable), declared by this service |
| Routing key | `request.created.<type>` (e.g. `request.created.water`) |
| Delivery | persistent, publisher-confirmed, `message-id` = `eventId` |
| Body | `{eventId, eventType: "RequestCreated", schemaVersion: 1, occurredAt, request}`; `request` is the full API representation, with `version` |

The Matching Service owns the queue `matching.request-created`, its binding
`request.created.*` and the dead-letter queue (names in `common.events.EventTopology`).

---

## 3. Code layout

```
services/request_service/src/main/java/com/emergency/request/
  RequestServiceApplication.java
  api/        RequestController, CreateRequestBody, StatusUpdateBody, ApiExceptionHandler (error JSON)
  domain/     RequestService (business logic), DetailsValidator, ports RequestRepository and
              RequestEventPublisher, StoredRequest, NewRequest, domain exceptions
  storage/    StorageRequestRepository: RequestRepository on the coordinator (keys, indexes)
  messaging/  RabbitRequestEventPublisher: RequestEventPublisher on RabbitMQ (confirms)
  config/     ServiceConfig (wiring), RabbitConfig (exchange), RequestServiceProperties,
              StorageHealthIndicator
common/       shared module: StorageClient, StorageKeys, GeoHash, GeoDistance, Category,
              Location, ErrorResponse, RequestDocument/RequestView/RequestStatus, events
```

The domain classes have no Spring annotations and know nothing about HTTP or AMQP.
They talk only to the two ports, so the business logic is tested with in-memory fakes
and the adapters are tested separately (spec §6: "business logic must not depend on
HTTP or AMQP classes").

---

## 4. Configuration (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `COORDINATOR_URL` | `http://localhost:9090` | storage coordinator |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | broker |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `guest` / `guest` | broker credentials (Compose passes its own) |
| `GEOHASH_PRECISION` | `6` | cell size; must be the same in every service |
| `STORAGE_TIMEOUT` | `10s` | bound on each storage call |
| `PUBLISH_CONFIRM_TIMEOUT` | `5s` | how long to wait for RabbitMQ's confirm |

Bad values stop the service at startup (validated settings).

Operations endpoints: `/actuator/health/liveness`, `/actuator/health/readiness` (needs
RabbitMQ and the coordinator; used by Docker's healthcheck), `/actuator/prometheus`.

---

## 5. Running it

```bash
make images     # all images, including emergency/request-service:local
make up-dev     # whole stack; gateway on http://localhost:8080, RabbitMQ UI on 127.0.0.1:15672
make request-smoke
make down
```

Docker image: multi-stage (Maven + JDK 21 → JRE 21), runs as a non-root user, about
140 MB, heap sized from the container limit (`-XX:MaxRAMPercentage=75`). Build from the
repository root: `docker build -f services/request_service/Dockerfile .`

---

## 6. Tests

`make test-java` (needs JDK 21 and Docker; without Docker the RabbitMQ tests are
*skipped*, so check the output for "Skipped"): **93 tests here + 47 in `common`, all passing.**

| Suite | Tests | What it proves |
|---|---|---|
| `DetailsValidatorTest` | 28 | every per-type rule; normalisation; unknown fields rejected |
| `RequestServiceTest` | 33 | create; compensation when publishing fails; storage down; every legal (7) and illegal (14) transition; reserved statuses; no-op; re-read on concurrent change; giving up after 3 conflicts |
| `RequestControllerTest` | 22 | status codes and error JSON for 17 kinds of bad body (including numbers sent as text), domain errors, 404/405/415, no internal details leaked |
| `StorageRequestRepositoryTest` | 3 | exact key, indexes and `expectedVersion` sent; storage outcomes → domain outcomes |
| `RabbitRequestEventPublisherTest` | 4 | real RabbitMQ: routing key per type, persistent, message-id, body; missing exchange and dead broker → 503 |
| `RequestServiceApplicationTest` | 3 | whole app with real RabbitMQ and a fake coordinator: create → stored → event → read → status; storage down → 503 and not ready; health and metrics |
| `common` module | 47 | geohash against reference values, storage client against a mock coordinator, key formats, status tables, JSON shapes, numbers-as-text rule |

End to end (`make request-smoke`, against the real Go storage cluster): creates all 5
types, checks the stored record in the coordinator, checks the events through the
RabbitMQ API, status rules, and 9 error cases. Also checked by hand on the live stack:
RabbitMQ down → 503 with nothing left in storage; coordinator down → 503; process
killed (`kill 1`) → Docker restarts it; service stopped → gateway answers the JSON 503;
everything recovers on its own.

Bugs found by these tests and fixed before the first commit:
1. `Location.isValid()` was serialised by Jackson as a `"valid": true` field inside every
   stored location, and reading it back then failed (500). Renamed to `inRange()`.
2. A missing RabbitMQ exchange raised a raw `ShutdownSignalException` (500 instead of 503).
3. Spring encoded `:` in storage keys as `%3A`; keys are now validated and sent as-is.

---

## 7. Design decisions (for the viva)

| Decision | Why |
|---|---|
| Domain behind two ports (repository, publisher) | business logic testable without HTTP, AMQP or Docker; adapters swappable |
| Delete the request if the event cannot be published | otherwise a PENDING request nobody will ever match, and a retry creates a duplicate |
| Publisher confirms (`simple`), wait before answering 201 | "201" only once RabbitMQ has taken responsibility for the event |
| Persistent messages + mandatory flag | events survive a broker restart; unroutable events are logged, not silently dropped |
| Read → check → write with `expectedVersion`, retry on 409 | two writers (a client and the Matching Service) can never overwrite each other |
| MATCHED/UNMATCHED reserved for matching | MATCHED must carry `assignedResourceId`, written in the same conditional write as the claim |
| Same-status update is a no-op 200 | PUT is idempotent; safe to retry after a timeout |
| Strict JSON in API bodies, tolerant JSON when reading storage | typos are errors for clients; a new field added by another service never breaks a read |
| `version` not stored in the document | it would go stale; storage owns it |
| Geohash written by us in `common` (no library) | every service must compute identical cells, and it can be explained line by line |

---

## 8. Spec checklist (CP1 §7.1-C)

| Requirement | Status |
|---|---|
| POST, GET, PUT status per §4.1 | done |
| Validation per type, status-transition rules, server-side geohash | done |
| Stores via `StorageClient` (in `common/`), then publishes `RequestCreated` (persistent, publisher confirms) | done |
| Uniform error JSON; 503 when coordinator/RabbitMQ unreachable | done |
| Declares the exchange on startup | done |
| Health + Prometheus endpoints | done |
| Dockerfile: multi-stage, JRE 21, non-root | done |
| Compose: healthcheck, `restart: unless-stopped`, memory limit, env-only config | done |
| Gateway route `/requests` (nginx) | done |
| Contracts in `contracts/` match the implementation | done (checked by tests and the smoke script) |

---

## 9. What's left

| # | Item | Who / when | Notes |
|---|---|---|---|
| 1 | Merge `feat/request-service` into `main` | now | The Resource Service branch needs `common` from main (see `docs/service-interfaces.md` §5) |
| 2 | Agree resource `capacity.unit` per type | Request + Resource | Proposal in `docs/service-interfaces.md` §3 (L, MEALS, UNITS, PEOPLE) |
| 3 | Matching Service writes MATCHED/UNMATCHED and `assignedResourceId` | Matching branch | Rules in `docs/service-interfaces.md` §4; until its queue exists, events are not routed (logged as a warning) |
| 4 | Kubernetes: Deployment (≥2 replicas), Service, HTTPRoute, probes on `/actuator/health/*` | CP2 | Image, health endpoints and env-only config are ready for it |
| 5 | Crash between "store" and "publish" leaves a PENDING request without an event | future work (spec §5) | Fix would be an outbox or a reconciliation job; documented gap |
| 6 | `POST /requests` is not idempotent: a client retrying after a timeout may create a duplicate | future work | Could add an `Idempotency-Key` header |
| 7 | Cancelling a MATCHED request does not give the resource capacity back | Matching / CP2+ | Spec lists capacity release as out of scope for CP1 |
| 8 | No `GET /requests` listing, no authentication | not required | Not in the spec's API table; auth out of scope (spec §7.2) |
| 9 | Automated contract check (validate responses against the OpenAPI file in tests) | nice to have | Today checked by hand-written assertions |
| 10 | Request Service on Kubernetes not tested yet (and the storage tier on minikube never deployed) | CP2 | — |
