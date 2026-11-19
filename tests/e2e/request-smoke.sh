#!/usr/bin/env bash
# Request Service end-to-end test, through the gateway, against the real storage cluster
# and RabbitMQ (spec §7.1-C, §11 "request creation, retrieval, and status updates",
# "RabbitMQ message delivery"):
#
#   1. Create requests of every type → 201, server-computed geoCell, PENDING, version 1.
#   2. The stored record has the format of docs/data-format.md §5 (read from the coordinator).
#   3. A RequestCreated event reaches a queue bound to request.created.* (RabbitMQ API).
#   4. Read back; status changes: legal, idempotent, reserved (MATCHED), illegal (409).
#   5. Invalid input is 400, unknown IDs 404, all with the standard error JSON.
#
# Usage:  BASE_URL=http://localhost:8080 tests/e2e/request-smoke.sh
# Needs:  bash 4+, curl, jq, and the stack started with `make up-dev` (which publishes
#         the coordinator on :9090 and the RabbitMQ management API on :15672).

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
COORDINATOR_URL="${COORDINATOR_URL:-http://localhost:9090}"
RABBIT_API="${RABBIT_API:-http://localhost:15672/api}"
RABBIT_AUTH="${RABBITMQ_USER:-emergency}:${RABBITMQ_PASSWORD:-emergency}"
QUEUE="smoke.request-created.$(date +%s)-${RANDOM}"

TMP="$(mktemp -d)"
# ${TMP:?} aborts instead of expanding to an empty path.
trap 'curl -s -o /dev/null -u "$RABBIT_AUTH" -X DELETE "$RABBIT_API/queues/%2F/$QUEUE"; rm -rf -- "${TMP:?}"' EXIT
BODY="$TMP/body"
HEADERS="$TMP/headers"

STATUS=""
call() { # call METHOD URL [JSON] — sets $STATUS; body in $BODY, headers in $HEADERS
  local method=$1 url=$2 data=${3:-}
  local args=(-sS -o "$BODY" -D "$HEADERS" -w '%{http_code}' -X "$method" "$url")
  [[ -n "$data" ]] && args+=(-H 'Content-Type: application/json' --data "$data")
  STATUS="$(curl "${args[@]}")" || { echo "FAIL: cannot reach $url" >&2; exit 1; }
}
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }
expect() { [[ "$STATUS" == "$1" ]] || fail "$2: expected HTTP $1, got $STATUS: $(cat "$BODY")"; ok "$2 (HTTP $STATUS)"; }
field() { jq -r "$1" "$BODY"; }
expect_field() { local got; got="$(field "$1")"; [[ "$got" == "$2" ]] || fail "$3: $1 = $got, want $2"; ok "$3"; }
expect_error() { # expect_error STATUS CODE DESCRIPTION
  expect "$1" "$3"
  [[ "$(field .error)" == "$2" && -n "$(field .message)" && -n "$(field .timestamp)" ]] ||
    fail "$3: error body is not {error: $2, message, timestamp}: $(cat "$BODY")"
}

echo "Request Service smoke test against ${BASE_URL}"
call GET "$BASE_URL/healthz"
expect 200 "gateway is up"

echo "== RabbitMQ: bind a test queue the way the Matching Service will"
curl -sf -u "$RABBIT_AUTH" -X PUT "$RABBIT_API/queues/%2F/$QUEUE" -H 'Content-Type: application/json' \
  -d '{"durable":false,"auto_delete":false}' >/dev/null || fail "cannot create test queue via $RABBIT_API"
curl -sf -u "$RABBIT_AUTH" -X POST "$RABBIT_API/bindings/%2F/e/emergency.events/q/$QUEUE" -H 'Content-Type: application/json' \
  -d '{"routing_key":"request.created.*"}' >/dev/null || fail "cannot bind test queue (does exchange emergency.events exist?)"
ok "queue $QUEUE bound to emergency.events with request.created.*"

echo "== 1. Create"
call POST "$BASE_URL/requests" '{"type":"WATER","location":{"lat":12.9716,"lng":77.5946},"details":{"quantity":20},"requester":{"name":"Ravi Kumar","contact":"+91-90000-00000"}}'
expect 201 "create a WATER request"
ID="$(field .requestId)"
[[ "$ID" =~ ^[0-9a-f-]{36}$ ]] || fail "requestId $ID is not a UUID"
grep -qi "^location: /requests/$ID" "$HEADERS" || fail "Location header missing or wrong"
ok "Location: /requests/$ID"
expect_field .geoCell tdr1v9 "geoCell computed by the server (precision 6)"
expect_field .status PENDING "new request is PENDING"
expect_field .version 1 "version 1"
expect_field .details.unit L "default unit filled in"
CREATED="$(cat "$BODY")"

for doc in \
  '{"type":"FOOD","location":{"lat":12.97,"lng":77.59},"details":{"quantity":10}}' \
  '{"type":"MEDICINE","location":{"lat":12.97,"lng":77.59},"details":{"name":"Insulin","quantity":2}}' \
  '{"type":"TRANSPORT","location":{"lat":12.97,"lng":77.59},"details":{"people":4,"destination":{"lat":12.93,"lng":77.62}}}' \
  '{"type":"SHELTER","location":{"lat":12.97,"lng":77.59},"details":{"people":6}}'; do
  call POST "$BASE_URL/requests" "$doc"
  expect 201 "create a $(jq -r .type <<<"$doc") request"
done

echo "== 2. Stored in the documented format (read from the coordinator)"
call GET "$COORDINATOR_URL/v1/data/request:$ID"
expect 200 "record exists under request:{requestId}"
expect_field '.indexes | to_entries | map("\(.key)=\(.value)") | sort | join(",")' \
  "collection=request,geoCell=tdr1v9,status=PENDING,type=WATER" "indexes: collection, type, status, geoCell"
expect_field '.value | has("version")' false "version is not stored inside the value"
expect_field '.value.location | keys | join(",")' "lat,lng" "location is exactly {lat, lng}"
expect_field '.value.requestId' "$ID" "value.requestId matches the key"

echo "== 3. RequestCreated delivered to RabbitMQ"
got=""
for _ in 1 2 3 4 5; do
  got="$(curl -sf -u "$RABBIT_AUTH" -X POST "$RABBIT_API/queues/%2F/$QUEUE/get" -H 'Content-Type: application/json' \
    -d '{"count":10,"ackmode":"ack_requeue_false","encoding":"auto"}')" || true
  [[ "$(jq length <<<"$got")" -ge 1 ]] && break
  sleep 1
done
[[ "$(jq length <<<"$got")" -ge 5 ]] || fail "expected 5 events, got $(jq length <<<"$got")"
event="$(jq -c --arg id "$ID" '[.[] | select((.payload | fromjson).request.requestId == $id)][0]' <<<"$got")"
[[ "$event" != null ]] || fail "no event for request $ID"
[[ "$(jq -r .routing_key <<<"$event")" == request.created.water ]] || fail "routing key: $(jq -r .routing_key <<<"$event")"
[[ "$(jq -r .properties.delivery_mode <<<"$event")" == 2 ]] || fail "event is not persistent"
payload="$(jq -r .payload <<<"$event")"
[[ "$(jq -r .eventType <<<"$payload")" == RequestCreated && "$(jq -r .schemaVersion <<<"$payload")" == 1 ]] ||
  fail "event envelope: $payload"
[[ "$(jq -r .properties.message_id <<<"$event")" == "$(jq -r .eventId <<<"$payload")" ]] || fail "message_id != eventId"
ok "5 events; WATER one on request.created.water, persistent, message_id = eventId, schemaVersion 1"
ok "event types: $(jq -r '[.[].routing_key] | sort | join(" ")' <<<"$got")"

echo "== 4. Read and change status"
call GET "$BASE_URL/requests/$ID"
expect 200 "read it back"
[[ "$(jq -S . "$BODY")" == "$(jq -S . <<<"$CREATED")" ]] || fail "GET differs from the POST response"
ok "GET returns exactly what POST returned"
call GET "$BASE_URL/requests/${ID^^}"
expect 200 "IDs are case-insensitive"

call PUT "$BASE_URL/requests/$ID/status" '{"status":"MATCHED"}'
expect_error 400 INVALID_REQUEST "MATCHED is reserved for the Matching Service"
call PUT "$BASE_URL/requests/$ID/status" '{"status":"FULFILLED"}'
expect_error 409 CONFLICT "PENDING -> FULFILLED is illegal"
call PUT "$BASE_URL/requests/$ID/status" '{"status":"CANCELLED"}'
expect 200 "PENDING -> CANCELLED"
expect_field .version 2 "version incremented"
call PUT "$BASE_URL/requests/$ID/status" '{"status":"CANCELLED"}'
expect 200 "setting CANCELLED again is a no-op (safe retry)"
expect_field .version 2 "no-op wrote nothing"
call PUT "$BASE_URL/requests/$ID/status" '{"status":"PENDING"}'
expect_error 409 CONFLICT "CANCELLED is final"
call GET "$COORDINATOR_URL/v1/data/request:$ID"
expect_field .indexes.status CANCELLED "stored index status kept in sync with the value"

echo "== 5. Invalid input and unknown IDs"
call POST "$BASE_URL/requests" '{"type":"water","location":{"lat":1,"lng":2},"details":{"quantity":1}}'
expect_error 400 INVALID_REQUEST "lower-case type"
call POST "$BASE_URL/requests" '{"type":"WATER","location":{"lat":1,"lng":2},"details":{"quantity":1},"geoCell":"tdr1v9"}'
expect_error 400 INVALID_REQUEST "client may not send geoCell"
call POST "$BASE_URL/requests" '{"type":"WATER","location":{"lat":95,"lng":2},"details":{"quantity":1}}'
expect_error 400 INVALID_REQUEST "latitude out of range"
call POST "$BASE_URL/requests" '{"type":"FOOD","location":{"lat":1,"lng":2},"details":{"quantity":2.5}}'
expect_error 400 INVALID_REQUEST "FOOD quantity must be whole"
call POST "$BASE_URL/requests" '{"type":"SHELTER","location":{"lat":1,"lng":2},"details":{"quantity":3}}'
expect_error 400 INVALID_REQUEST "wrong details fields for the type"
call POST "$BASE_URL/requests" 'not json'
expect_error 400 INVALID_REQUEST "body is not JSON"
call GET "$BASE_URL/requests/00000000-0000-4000-8000-000000000000"
expect_error 404 NOT_FOUND "unknown request"
call GET "$BASE_URL/requests/not-a-uuid"
expect_error 400 INVALID_REQUEST "malformed request ID"
call GET "$BASE_URL/nope"
expect_error 404 NOT_FOUND "unknown path at the gateway"

echo
echo "PASS: request service smoke test"
