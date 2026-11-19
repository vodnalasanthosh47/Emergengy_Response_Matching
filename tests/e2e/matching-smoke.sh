#!/usr/bin/env bash
# Matching end-to-end test through the gateway, against the real storage cluster and
# RabbitMQ (spec §7.1-H smoke test and the CP1 Definition of Done):
#
#   A. The spec's smoke test: register a WATER resource (500 L), create a WATER request
#      (20 L) nearby → RequestCreated → Matching → the request becomes MATCHED with
#      assignedResourceId, and the resource has 480 L left.
#   B. No double booking: two simultaneous requests for a resource that can serve only
#      one → exactly one MATCHED, the other UNMATCHED.
#   C. Re-publishing the same RequestCreated event does not cause a second claim.
#   D. POST /match/{id}: 200 when a resource appears for an UNMATCHED request, 409 once
#      matched, 422 when nothing fits, 404 / 400 for unknown / malformed IDs.
#   E. No message ended up in the dead-letter queue.
#
# Every run uses a random location in central India, so resources left by other runs
# can never be candidates.
#
# Usage:  BASE_URL=http://localhost:8080 tests/e2e/matching-smoke.sh
# Needs:  bash 4+, curl, jq, and `make up-dev` (RabbitMQ API on 127.0.0.1:15672).

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
RABBIT_API="${RABBIT_API:-http://localhost:15672/api}"
RABBIT_AUTH="${RABBITMQ_USER:-emergency}:${RABBITMQ_PASSWORD:-emergency}"
LAT="$(printf '23.%04d' $(( RANDOM % 9000 + 500 )))"
LNG="$(printf '80.%04d' $(( RANDOM % 9000 + 500 )))"
NEAR_LAT="$(printf '%.4f' "$(echo "$LAT + 0.0010" | bc)")" # ~110 m north

TMP="$(mktemp -d)"
trap 'rm -rf -- "${TMP:?}"' EXIT
BODY="$TMP/body"

STATUS=""
call() { # call METHOD PATH [JSON] — sets $STATUS, body in $BODY
  local method=$1 path=$2 data=${3:-}
  local args=(-sS -o "$BODY" -w '%{http_code}' -X "$method" "${BASE_URL}${path}")
  [[ -n "$data" ]] && args+=(-H 'Content-Type: application/json' --data "$data")
  STATUS="$(curl "${args[@]}")" || { echo "FAIL: cannot reach ${BASE_URL}${path}" >&2; exit 1; }
}
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }
step() { echo; echo "== $*"; }
field() { jq -r "$1" "$BODY"; }
expect() { [[ "$STATUS" == "$1" ]] || fail "$2: expected HTTP $1, got $STATUS: $(cat "$BODY")"; ok "$2 (HTTP $STATUS)"; }

register() { # register TYPE TOTAL [DETAILS] [LAT] — prints the resourceId
  local details=${3:-'{}'} lat=${4:-$NEAR_LAT}
  call POST /resources "{\"type\":\"$1\",\"location\":{\"lat\":$lat,\"lng\":$LNG},\"provider\":{\"name\":\"Matching smoke\",\"kind\":\"NGO\"},\"capacity\":{\"total\":$2},\"details\":$details}"
  [[ "$STATUS" == 201 ]] || fail "register $1: HTTP $STATUS $(cat "$BODY")"
  field .resourceId
}
create() { # create TYPE DETAILS — prints the requestId
  call POST /requests "{\"type\":\"$1\",\"location\":{\"lat\":$LAT,\"lng\":$LNG},\"details\":$2}"
  [[ "$STATUS" == 201 ]] || fail "create $1 request: HTTP $STATUS $(cat "$BODY")"
  field .requestId
}
status_of() { curl -s "${BASE_URL}/requests/$1" | jq -r .status; }
wait_settled() { # wait_settled REQUEST_ID — until matching has decided (MATCHED or UNMATCHED)
  local start=$SECONDS s
  while :; do
    s="$(status_of "$1")"
    [[ "$s" == MATCHED || "$s" == UNMATCHED ]] && { echo "$s"; return; }
    (( SECONDS - start > 30 )) && fail "request $1 still $s after 30s"
    sleep 0.5
  done
}
remaining() { curl -s "${BASE_URL}/resources/$1" | jq -r .capacity.remaining; }
dlq_count() { curl -s -u "$RABBIT_AUTH" "$RABBIT_API/queues/%2F/matching.request-created.dlq" | jq -r '.messages // 0'; }

echo "Matching smoke test against ${BASE_URL} (location ${LAT}, ${LNG})"
call GET /healthz
expect 200 "gateway is up"
dlq_before="$(dlq_count)"

step "A. Request → RabbitMQ → Matching → MATCHED (the spec's smoke test)"
WATER_RES="$(register WATER 500)"
ok "registered WATER resource $WATER_RES (500 L)"
REQ_A="$(create WATER '{"quantity":20}')"
ok "created WATER request $REQ_A (20 L)"
start=$SECONDS
[[ "$(wait_settled "$REQ_A")" == MATCHED ]] || fail "request $REQ_A was not MATCHED"
ok "request MATCHED by the event consumer after $(( SECONDS - start ))s"
call GET "/requests/$REQ_A"
[[ "$(field .assignedResourceId)" == "$WATER_RES" ]] || fail "assignedResourceId is $(field .assignedResourceId)"
ok "assignedResourceId = $WATER_RES"
[[ "$(remaining "$WATER_RES")" == 480 ]] || fail "remaining is $(remaining "$WATER_RES"), want 480"
ok "resource remaining = 480"
REQ_A_JSON="$(cat "$BODY")"
REQ_A_VERSION="$(field .version)"

step "B. Two simultaneous requests, capacity for one: no double booking"
SHELTER_RES="$(register SHELTER 30)"
ok "registered SHELTER resource $SHELTER_RES (30 places)"
for i in 1 2; do
  curl -s -o "$TMP/req$i" -X POST "${BASE_URL}/requests" -H 'Content-Type: application/json' \
    -d "{\"type\":\"SHELTER\",\"location\":{\"lat\":$LAT,\"lng\":$LNG},\"details\":{\"people\":20}}" &
done
wait
B1="$(jq -r .requestId "$TMP/req1")"; B2="$(jq -r .requestId "$TMP/req2")"
s1="$(wait_settled "$B1")"; s2="$(wait_settled "$B2")"
[[ "$(printf '%s\n' "$s1" "$s2" | sort | tr '\n' ' ')" == "MATCHED UNMATCHED " ]] ||
  fail "statuses $s1 / $s2, want exactly one MATCHED and one UNMATCHED"
ok "one MATCHED, one UNMATCHED ($s1 / $s2)"
[[ "$(remaining "$SHELTER_RES")" == 10 ]] || fail "shelter remaining $(remaining "$SHELTER_RES"), want 10"
ok "shelter remaining = 10 (claimed once)"
if [[ "$s1" == UNMATCHED ]]; then UNMATCHED_REQ="$B1"; else UNMATCHED_REQ="$B2"; fi

step "C. Re-publish request A's RequestCreated event: no second claim"
event="$(jq -c --arg id "$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)" \
  '{eventId: ($id | ascii_downcase), eventType: "RequestCreated", schemaVersion: 1, occurredAt: (now | todate), request: .}' <<<"$REQ_A_JSON")"
publish="$(jq -n --arg p "$event" '{properties: {content_type: "application/json", delivery_mode: 2}, routing_key: "request.created.water", payload: $p, payload_encoding: "string"}')"
for _ in 1 2; do
  routed="$(curl -s -u "$RABBIT_AUTH" -X POST "$RABBIT_API/exchanges/%2F/emergency.events/publish" -H 'Content-Type: application/json' -d "$publish" | jq -r .routed)"
  [[ "$routed" == true ]] || fail "re-published event was not routed to the matching queue"
done
ok "same event published twice more (routed to matching.request-created)"
sleep 3
call GET "/requests/$REQ_A"
[[ "$(field .version)" == "$REQ_A_VERSION" && "$(field .status)" == MATCHED ]] || fail "request A changed: $(cat "$BODY")"
[[ "$(remaining "$WATER_RES")" == 480 ]] || fail "remaining is $(remaining "$WATER_RES") after redelivery, want 480"
ok "request unchanged (version $REQ_A_VERSION), resource still 480: no second claim"

step "D. POST /match/{requestId}"
call POST "/match/$UNMATCHED_REQ"
expect 422 "still nothing for the UNMATCHED request"
[[ "$(field .error)" == NO_MATCH ]] || fail "error is $(field .error)"
NEW_SHELTER="$(register SHELTER 20)"
call POST "/match/$UNMATCHED_REQ"
expect 200 "a new resource appeared: matched now"
[[ "$(field .resourceId)" == "$NEW_SHELTER" && "$(field .request.status)" == MATCHED ]] || fail "result: $(cat "$BODY")"
ok "assigned to $NEW_SHELTER, $(field .distanceKm) km away, resource now $(field .resource.status)"
call POST "/match/$UNMATCHED_REQ"
expect 409 "matching a MATCHED request again is refused"
REQ_MED="$(create MEDICINE '{"name":"Matching smoke rare drug","quantity":1}')"
[[ "$(wait_settled "$REQ_MED")" == UNMATCHED ]] || fail "medicine request should be UNMATCHED"
call POST "/match/$REQ_MED"
expect 422 "nothing offers this medicine"
call POST "/match/00000000-0000-4000-8000-000000000000"
expect 404 "unknown request"
call POST "/match/not-a-uuid"
expect 400 "malformed request ID"

step "E. Dead-letter queue"
[[ "$(dlq_count)" == "$dlq_before" ]] || fail "dead-letter queue grew from $dlq_before to $(dlq_count)"
ok "no message dead-lettered ($(dlq_count) in the DLQ)"

echo
echo "PASS: matching smoke test"
