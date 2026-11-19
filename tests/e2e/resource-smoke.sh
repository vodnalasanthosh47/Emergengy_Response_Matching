#!/usr/bin/env bash
# Resource Service end-to-end test, through the gateway, against the real storage cluster
# (spec §7.1-D, §11 "resource registration and availability updates", "correct shard
# selection and data distribution"):
#
#   1. Register resources of every type → 201, server-computed geoCell and resourceId,
#      AVAILABLE, remaining defaulting to total.
#   2. The stored record has the format of docs/data-format.md §6 (read from the coordinator).
#   3. Resources in cells owned by different shards land on those shards (topology counts).
#   4. Read, list with filters, status changes: legal, idempotent, reserved, illegal.
#   5. Invalid input is 400, unknown IDs 404, all with the standard error JSON.
#
# Usage:  BASE_URL=http://localhost:8080 tests/e2e/resource-smoke.sh
# Needs:  bash 4+, curl, jq, python3, and the stack started with `make up-dev` (which
#         publishes the coordinator on :9090). Assumes no other writes while it runs.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
COORDINATOR_URL="${COORDINATOR_URL:-http://localhost:9090}"

TMP="$(mktemp -d)"
trap 'rm -rf -- "${TMP:?}"' EXIT
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
key_of() { # res_{cell}_{uuid} → resource:{cell}:{uuid}
  local rest=${1#res_}
  echo "resource:${rest%%_*}:${rest#*_}"
}
shard_counts() { # live records on each shard's primary, e.g. "3 5 1"
  curl -sf "$COORDINATOR_URL/internal/topology" |
    jq -r '[.shards | sort_by(.shard)[] | .primary as $p | .nodes[] | select(.nodeId == $p) | .records] | join(" ")'
}

echo "Resource Service smoke test against ${BASE_URL}"
call GET "$BASE_URL/healthz"
expect 200 "gateway is up"

echo "== 1. Register"
call POST "$BASE_URL/resources" '{"type":"WATER","location":{"lat":12.9716,"lng":77.5946},"provider":{"name":"Akshaya NGO","kind":"NGO","contact":"+91-80000-00000"},"capacity":{"total":500}}'
expect 201 "register a WATER resource"
ID="$(field .resourceId)"
[[ "$ID" =~ ^res_tdr1v9_[0-9a-f-]{36}$ ]] || fail "resourceId $ID is not res_tdr1v9_{uuid}"
ok "resourceId $ID"
grep -qi "^location: /resources/$ID" "$HEADERS" || fail "Location header missing or wrong"
ok "Location: /resources/$ID"
expect_field .geoCell tdr1v9 "geoCell computed by the server, same as the Request Service's"
expect_field .status AVAILABLE "new resource is AVAILABLE"
expect_field .version 1 "version 1"
expect_field '.capacity | "\(.total) \(.remaining) \(.unit)"' "500 500 L" "remaining defaults to total; unit filled in"
CREATED="$(cat "$BODY")"

call POST "$BASE_URL/resources" '{"type":"FOOD","location":{"lat":12.97,"lng":77.59},"provider":{"name":"Depot","kind":"GOVT"},"capacity":{"total":100,"remaining":60}}'
expect 201 "register FOOD with explicit remaining"
expect_field '.capacity | "\(.total) \(.remaining) \(.unit)"' "100 60 MEALS" "explicit remaining kept"
call POST "$BASE_URL/resources" '{"type":"MEDICINE","location":{"lat":12.97,"lng":77.59},"provider":{"name":"Clinic","kind":"OTHER"},"capacity":{"total":40},"details":{"name":" Insulin "}}'
expect 201 "register MEDICINE"
expect_field '"\(.details.name) \(.capacity.unit)"' "Insulin UNITS" "medicine name trimmed, unit UNITS"
call POST "$BASE_URL/resources" '{"type":"TRANSPORT","location":{"lat":12.97,"lng":77.59},"provider":{"name":"Ravi","kind":"VOLUNTEER"},"capacity":{"total":4}}'
expect 201 "register TRANSPORT"
call POST "$BASE_URL/resources" '{"type":"SHELTER","location":{"lat":12.97,"lng":77.59},"provider":{"name":"School","kind":"GOVT"},"capacity":{"total":80,"unit":"PEOPLE"}}'
expect 201 "register SHELTER"

echo "== 2. Stored in the documented format (read from the coordinator)"
KEY="$(key_of "$ID")"
call GET "$COORDINATOR_URL/v1/data/$KEY"
expect 200 "record exists under $KEY"
expect_field '.indexes | to_entries | map("\(.key)=\(.value)") | sort | join(",")' \
  "collection=resource,geoCell=tdr1v9,status=AVAILABLE,type=WATER" "indexes: collection, type, status, geoCell"
expect_field '.value | has("version")' false "version is not stored inside the value"
expect_field '.value.capacity.total | tostring' 500 "integer stays an integer (500, not 500.0)"
expect_field '.value.resourceId' "$ID" "value.resourceId matches the key"

echo "== 3. Shard selection: resources go to the shard owning their cell"
before=($(shard_counts))
declare -A want=([0]=0 [1]=0 [2]=0)
for loc in "12.9666 77.58" "12.9721 77.5909" "12.9776 77.6019"; do
  read -r lat lng <<<"$loc"
  call POST "$BASE_URL/resources" "{\"type\":\"WATER\",\"location\":{\"lat\":$lat,\"lng\":$lng},\"provider\":{\"name\":\"Shard test\",\"kind\":\"OTHER\"},\"capacity\":{\"total\":10}}"
  expect 201 "register in cell $(field .geoCell)"
  shard="$(python3 -I -c 'import sys, zlib; print(zlib.crc32(sys.argv[1].encode()) % 3)' "$(field .geoCell)")"
  want[$shard]=$((want[$shard] + 1))
done
after=($(shard_counts))
for s in 0 1 2; do
  [[ $((after[s] - before[s])) -eq ${want[$s]} ]] ||
    fail "shard $s: records ${before[s]} -> ${after[s]}, expected +${want[$s]}"
done
ok "each shard's record count grew by the resources whose cell it owns (crc32(cell) mod 3): ${before[*]} -> ${after[*]}"

echo "== 4. Read, list and change status"
call GET "$BASE_URL/resources/$ID"
expect 200 "read it back"
[[ "$(jq -S . "$BODY")" == "$(jq -S . <<<"$CREATED")" ]] || fail "GET differs from the POST response"
ok "GET returns exactly what POST returned"

call GET "$BASE_URL/resources?type=WATER&status=AVAILABLE&limit=1000"
expect 200 "list WATER AVAILABLE"
[[ "$(field '[.resources[] | select(.resourceId == "'"$ID"'")] | length')" == 1 ]] || fail "resource missing from list"
[[ "$(field '[.resources[] | select(.type != "WATER" or .status != "AVAILABLE")] | length')" == 0 ]] ||
  fail "list contains resources that do not match the filters"
ok "listed, and only matching resources returned"

call PUT "$BASE_URL/resources/$ID/status" '{"status":"DEPLETED"}'
expect_error 400 INVALID_REQUEST "DEPLETED is reserved for the Matching Service"
call PUT "$BASE_URL/resources/$ID/status" '{"status":"OFFLINE"}'
expect 200 "AVAILABLE -> OFFLINE"
expect_field .version 2 "version incremented"
call PUT "$BASE_URL/resources/$ID/status" '{"status":"OFFLINE"}'
expect 200 "setting OFFLINE again is a no-op (safe retry)"
expect_field .version 2 "no-op wrote nothing"
call GET "$COORDINATOR_URL/v1/data/$KEY"
expect_field .indexes.status OFFLINE "stored index status kept in sync with the value"
call GET "$BASE_URL/resources?type=WATER&status=AVAILABLE&limit=1000"
[[ "$(field '[.resources[] | select(.resourceId == "'"$ID"'")] | length')" == 0 ]] || fail "OFFLINE resource still listed as AVAILABLE"
ok "OFFLINE resource no longer listed as AVAILABLE"
call PUT "$BASE_URL/resources/$ID/status" '{"status":"AVAILABLE"}'
expect 200 "OFFLINE -> AVAILABLE"

# A depleted resource (as the Matching Service would leave it) cannot be made AVAILABLE.
call GET "$COORDINATOR_URL/v1/data/$KEY"
version="$(field .version)"
depleted="$(jq -c '.value | .status = "DEPLETED" | .capacity.remaining = 0' "$BODY")"
call PUT "$COORDINATOR_URL/v1/data/$KEY" "{\"value\":$depleted,\"indexes\":{\"collection\":\"resource\",\"type\":\"WATER\",\"status\":\"DEPLETED\",\"geoCell\":\"tdr1v9\"},\"expectedVersion\":$version}"
expect 200 "simulate a claim that used up the capacity"
call PUT "$BASE_URL/resources/$ID/status" '{"status":"AVAILABLE"}'
expect_error 409 CONFLICT "DEPLETED with no remaining capacity cannot become AVAILABLE"
call PUT "$BASE_URL/resources/$ID/status" '{"status":"OFFLINE"}'
expect 200 "DEPLETED -> OFFLINE"

echo "== 5. Invalid input and unknown IDs"
call POST "$BASE_URL/resources" '{"type":"water","location":{"lat":1,"lng":2},"provider":{"name":"a","kind":"NGO"},"capacity":{"total":1}}'
expect_error 400 INVALID_REQUEST "lower-case type"
call POST "$BASE_URL/resources" '{"type":"WATER","location":{"lat":1,"lng":2},"provider":{"name":"a","kind":"NGO"},"capacity":{"total":1},"geoCell":"tdr1v9"}'
expect_error 400 INVALID_REQUEST "client may not send geoCell"
call POST "$BASE_URL/resources" '{"type":"WATER","location":{"lat":1,"lng":2},"provider":{"name":"a","kind":"NGO"},"capacity":{"total":1},"status":"OFFLINE"}'
expect_error 400 INVALID_REQUEST "client may not send status"
call POST "$BASE_URL/resources" '{"type":"WATER","location":{"lat":95,"lng":2},"provider":{"name":"a","kind":"NGO"},"capacity":{"total":1}}'
expect_error 400 INVALID_REQUEST "latitude out of range"
call POST "$BASE_URL/resources" '{"type":"FOOD","location":{"lat":1,"lng":2},"provider":{"name":"a","kind":"NGO"},"capacity":{"total":2.5}}'
expect_error 400 INVALID_REQUEST "FOOD capacity must be whole"
call POST "$BASE_URL/resources" '{"type":"WATER","location":{"lat":1,"lng":2},"provider":{"name":"a","kind":"NGO"},"capacity":{"total":10,"remaining":11}}'
expect_error 400 INVALID_REQUEST "remaining above total"
call POST "$BASE_URL/resources" '{"type":"WATER","location":{"lat":1,"lng":2},"provider":{"name":"a","kind":"NGO"},"capacity":{"total":10,"unit":"MEALS"}}'
expect_error 400 INVALID_REQUEST "wrong unit for the type"
call POST "$BASE_URL/resources" '{"type":"MEDICINE","location":{"lat":1,"lng":2},"provider":{"name":"a","kind":"NGO"},"capacity":{"total":10}}'
expect_error 400 INVALID_REQUEST "MEDICINE needs details.name"
call POST "$BASE_URL/resources" '{"type":"WATER","location":{"lat":1,"lng":2},"provider":{"name":"a","kind":"CHARITY"},"capacity":{"total":1}}'
expect_error 400 INVALID_REQUEST "unknown provider kind"
call POST "$BASE_URL/resources" 'not json'
expect_error 400 INVALID_REQUEST "body is not JSON"
call GET "$BASE_URL/resources?status=BUSY"
expect_error 400 INVALID_REQUEST "unknown status filter"
call GET "$BASE_URL/resources/res_tdr1v9_00000000-0000-4000-8000-000000000000"
expect_error 404 NOT_FOUND "unknown resource"
call GET "$BASE_URL/resources/not-an-id"
expect_error 400 INVALID_REQUEST "malformed resource ID"

echo
echo "PASS: resource service smoke test"
