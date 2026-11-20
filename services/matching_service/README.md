# Matching Service — status and handover

Matches emergency requests to resources (PROJECT_SPEC §5, §7.1-E; PDF §7). It consumes
`RequestCreated` events from RabbitMQ and also serves `POST /match/{requestId}`; both run
the same algorithm, which claims resource capacity through the storage coordinator
without ever double-booking.

**Status (2026-11-19): done for Checkpoint 1.** 171 tests in this service (+49 in
`common`) pass, and `make smoke` passes against the real storage cluster, RabbitMQ and
gateway. Branch `feat/matching-service` (local commits, not pushed). What is left is in
[§8](#8-whats-left).

Related: [`contracts/openapi/matching-service-api.yaml`](../../contracts/openapi/matching-service-api.yaml),
[`docs/service-interfaces.md`](../../docs/service-interfaces.md),
[`docs/data-format.md`](../../docs/data-format.md) §5–§7.

---

## 1. How a request gets matched

```
POST /requests ─► Request Service ─► RabbitMQ emergency.events ─request.created.*─► matching.request-created
                                                                                        │
POST /match/{id} ─────────────────────────────────────────────────────────► MatchingService.match(id)
                                                                                        │
                                       coordinator: read request, query resources, claim, update request
```

`MatchingService.match(requestId)` (spec §5):

1. **Re-read the request.** Only `PENDING` and `UNMATCHED` are matched; anything else is
   left alone. This is why a redelivered event or a repeated call never claims twice.
2. **Candidates:** `AVAILABLE` resources of the same type in the request's geohash cell
   and its 8 neighbours (`GeoHash.withNeighbours`). The query carries those 9 cells, so
   the coordinator asks only the shards owning them.
3. **Fit:** `capacity.remaining >= amount` (WATER/FOOD/MEDICINE: `details.quantity`;
   TRANSPORT/SHELTER: `details.people`); MEDICINE also the same name (trimmed,
   case-insensitive).
4. **Rank** (`CandidateRanking`): nearest first (haversine), then most remaining capacity,
   then resource ID.
5. **Claim** with a conditional write on the version just read: `remaining - amount`,
   `DEPLETED` at 0 (value and index). On `409` re-read the resource: claim again if it
   still fits, otherwise try the next. At most 5 claim attempts.
6. **Mark the request `MATCHED`** with `assignedResourceId`, again conditionally. If the
   request changed meanwhile and is no longer matchable (cancelled, or matched by another
   attempt), **release** the claim: add the amount back (never above `total`),
   `DEPLETED → AVAILABLE`, `OFFLINE` stays `OFFLINE`. If the MATCHED write fails with
   `503` or a timeout, its outcome is unknown (the coordinator may have applied it), so
   the request is re-read first: already assigned to this resource → keep the claim;
   still matchable → write MATCHED again; cancelled → release. Never confirmed → keep
   the claim and report the error (unused capacity is fixable, double booking is not).
7. **Nothing could be claimed:** the request becomes `UNMATCHED` (no write if it already is).

**Why there is no double booking:** capacity only ever changes through a conditional
write on the version just read. Two matchers that read the same version cannot both
succeed: the second gets `409`, re-reads, and sees the reduced capacity.

## 2. The event consumer (spec §4.5)

| Item | Value |
|---|---|
| Queue | `matching.request-created`, durable, bound to `emergency.events` with `request.created.*` |
| Dead letters | queue argument `x-dead-letter-exchange: emergency.dlx` (fanout) → `matching.request-created.dlq` |
| Ack | manual, prefetch 10 |

- **ack** when the attempt has a final outcome: MATCHED, UNMATCHED, not matchable (a
  redelivery) or the request no longer exists;
- **retry in place** (`EVENT_ATTEMPTS`, default 3, `EVENT_RETRY_DELAY` 1 s apart) while
  storage is unavailable or the request keeps changing;
- **nack without requeue → DLQ** after that, or for a malformed message (not JSON, not
  `RequestCreated` v1, no request ID). A dead-lettered request can be matched later with
  `POST /match/{id}`.

Only the event's request ID is used; the request is re-read, because the snapshot in the
event may be stale. The service declares the topology on startup (and the
`emergency.events` exchange too, with the Request Service's settings), so start order
does not matter. In Compose the gateway waits for this service, so the queue exists
before clients create requests.

## 3. API

`POST /match/{requestId}` (no body):

| Outcome | Status | Body |
|---|---|---|
| matched | `200` | `{requestId, resourceId, distanceKm, request, resource}` |
| no resource can serve it | `422 NO_MATCH` | error JSON; the request is now `UNMATCHED` |
| not PENDING/UNMATCHED | `409 CONFLICT` | error JSON naming the status |
| unknown request | `404 NOT_FOUND` | error JSON |
| ID is not a UUID | `400 INVALID_REQUEST` | error JSON |
| storage unavailable | `503 SERVICE_UNAVAILABLE` | error JSON |

## 4. Code layout

```
services/matching_service/src/main/java/com/emergency/matching/
  domain/     MatchingService (algorithm), CandidateRanking (fit + order), Demand (amount),
              ports RequestStore / ResourceStore, Versioned, MatchOutcome, exceptions
  storage/    StorageRequestStore, StorageResourceStore: the ports on the coordinator
  messaging/  RequestCreatedListener: manual ack, retries, DLQ
  api/        MatchController, MatchResult, ApiExceptionHandler
  config/     ServiceConfig (wiring), RabbitTopologyConfig (queue, DLX, DLQ, bindings),
              MatchingServiceProperties, JacksonConfig, StorageHealthIndicator
```

The domain has no Spring annotations and does not know HTTP or AMQP.

## 5. Configuration

| Variable | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `COORDINATOR_URL` | `http://localhost:9090` | storage coordinator |
| `RABBITMQ_HOST` / `PORT` / `USER` / `PASSWORD` | `localhost` / `5672` / `guest` / `guest` | broker |
| `STORAGE_TIMEOUT` | `10s` | bound on each storage call |
| `CANDIDATE_LIMIT` | `100` | resources fetched from the 9 cells |
| `EVENT_ATTEMPTS` / `EVENT_RETRY_DELAY` | `3` / `1s` | in-place retries before the DLQ |

Health: `/actuator/health/readiness` needs RabbitMQ and the coordinator;
`/actuator/prometheus` for metrics.

## 6. Tests

`make test-java` (needs JDK 21 and Docker; without Docker the RabbitMQ tests are skipped):

| Suite | Tests | What it proves |
|---|---|---|
| `DemandTest` | 9 | amount per type, medicine name, corrupt details rejected |
| `CandidateRankingTest` | 6 | nearest fitting first, exact fit, tie-breaks, type/status filter, medicine names |
| `MatchingServiceTest` | 25 | happy path; deplete at 0; decimals exact; UNMATCHED; re-match later; every non-matchable status untouched; same request twice; claim conflict retried / skipped / capped; cancelled mid-match → released; MATCHED write with an unknown outcome (storage 503 / timeout): applied → claim kept, not applied → written again, cancelled → released, never confirmed → claim kept and reported |
| `MatchingConcurrencyTest` | 120 | real threads, repeated: 2 requests for 1 resource → one MATCHED; 10 requests for 3 places → exactly 3; the same request twice at once → one claim |
| `StorageStoresTest` | 5 | exact keys, bodies, indexes, `expectedVersion`; error mapping |
| `MatchControllerTest` | 6 | every outcome and exception → status and error JSON |
| `MatchingServiceApplicationTest` | 7 | real RabbitMQ: event → MATCHED; redelivery claims nothing; UNMATCHED; malformed → DLQ; storage down → DLQ, then POST /match; HTTP; health |

Mutation check: removing the release, or the re-check after a lost claim race, fails
52 / 68 of these tests.

End to end (`make matching-smoke`, real cluster): the spec's smoke test (500 L resource,
20 L request → MATCHED in about a second, 480 L left), two simultaneous requests for one
resource (one MATCHED, one UNMATCHED), the same event published twice more (no second
claim), `POST /match` 422 → 200 → 409, 404, 400, and an empty DLQ. Checked by hand:
`docker compose exec matching-service sh -c 'kill 1'` → Docker restarts it, healthy in
2 s, and the smoke test passes again.

## 7. CP1 Definition of Done (the matching parts)

| Item | Evidence |
|---|---|
| `make smoke` passes through the gateway | request, resource and matching smoke tests pass |
| Crashing matching-service → Docker restarts it; smoke passes afterwards | done by hand (§6) |
| Two simultaneous requests, one resource → one MATCHED, one UNMATCHED | `MatchingConcurrencyTest`, `matching-smoke` B |
| Re-publishing `RequestCreated` does not cause a second claim | `MatchingServiceApplicationTest`, `matching-smoke` C |
| Contracts match the implementation | `matching-service-api.yaml`; controller and e2e tests |

## 8. What's left

| # | Item | Notes |
|---|---|---|
| 1 | Push `feat/matching-service`, then merge everything into `main` | Branch is local only |
| 2 | Crash between "claim" and "mark MATCHED" loses that capacity | Documented gap (spec §5). A definite storage error there (409 then failed re-read, 400) releases the claim. A 503 or timeout has an unknown outcome (the write may have landed): the request is re-read and the MATCHED write retried, and if storage never confirms either way the claim is **kept** and logged, because giving it back could double-book. A process crash cannot release. Fix: outbox / reconciliation job |
| 3 | UNMATCHED requests are not re-matched automatically when a resource is registered | CP2+ (spec §7.2); use `POST /match/{id}` meanwhile |
| 4 | Cancelling or fulfilling a MATCHED request does not give capacity back | Out of scope for CP1 (spec §7.2) |
| 5 | Dead-lettered events are not replayed automatically | Inspect `matching.request-created.dlq` in the RabbitMQ UI; re-match with `POST /match/{id}` |
| 6 | No widening of the search to precision 5 when the 9 cells have nothing | Spec §3.4 "CP2+" |
| 7 | Kubernetes manifests (Deployment ≥2 replicas, Service, HTTPRoute) | CP2. Several replicas are safe: claims are compare-and-set, and each event is delivered to one consumer |
