#!/usr/bin/env bash
# Storage-tier smoke test: drives the coordinator data API end to end
# (coordinator → storage node → MongoDB) and checks the versioning rules and the key
# formats the shard is derived from:
#   request:{requestId}          sharded by a hash of the request ID
#   resource:{geohash}:{uuid}    sharded by a hash of the geohash cell
#
# Usage:  BASE_URL=http://localhost:9090 tests/e2e/storage-smoke.sh
# Needs:  curl, jq, and the stack started with `make up-dev`.
# Exits non-zero on the first failed check.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:9090}"
CELL="tdr1yz" # a geohash (precision 6), as the Resource Service computes it
KEY="resource:${CELL}:$(date +%s)-${RANDOM}"
MISSING="resource:${CELL}:does-not-exist-${RANDOM}"
REQUEST_KEY="request:smoke-$(date +%s)-${RANDOM}"

BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

STATUS=""
# call METHOD PATH [JSON] — sets $STATUS; the response body is in $BODY_FILE.
call() {
  local method=$1 path=$2 data=${3:-}
  local args=(-sS -o "$BODY_FILE" -w '%{http_code}' -X "$method" "${BASE_URL}${path}")
  if [[ -n "$data" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$data")
  fi
  STATUS="$(curl "${args[@]}")" || { echo "FAIL: cannot reach ${BASE_URL}${path}" >&2; exit 1; }
}

# expect STATUS DESCRIPTION — checks the last call's status code.
expect() {
  if [[ "$STATUS" != "$1" ]]; then
    echo "FAIL: $2 — expected HTTP $1, got $STATUS: $(cat "$BODY_FILE")" >&2
    exit 1
  fi
  echo "  ok  $2 (HTTP $STATUS)"
}

# expect_json JQ_FILTER EXPECTED DESCRIPTION — checks a field of the last response body.
expect_json() {
  local got
  got="$(jq -r "$1" "$BODY_FILE")"
  if [[ "$got" != "$2" ]]; then
    echo "FAIL: $3 — expected $1 == $2, got $got" >&2
    exit 1
  fi
  echo "  ok  $3"
}

resource() { # resource REMAINING [EXPECTED_VERSION]
  local ev=""
  [[ -n "${2:-}" ]] && ev=",\"expectedVersion\":$2"
  printf '{"value":{"type":"WATER","capacity":{"total":500,"remaining":%s,"unit":"L"}},"indexes":{"collection":"resource","type":"WATER","status":"AVAILABLE","geoCell":"%s"}%s}' \
    "$1" "$CELL" "$ev"
}

echo "Storage smoke test against ${BASE_URL} (key ${KEY})"

call GET /readyz
expect 200 "coordinator is ready (storage node reachable)"

call PUT "/v1/data/${KEY}" "$(resource 500 0)"
expect 201 "create-only write of a new key"
expect_json .version 1 "new record starts at version 1"

call PUT "/v1/data/${KEY}" "$(resource 500 0)"
expect 409 "create-only write of an existing key is rejected"
expect_json .error VERSION_CONFLICT "error body uses the standard shape"

call GET "/v1/data/${KEY}"
expect 200 "read it back"
expect_json .value.capacity.remaining 500 "value round-trips with integers intact"

call PUT "/v1/data/${KEY}" "$(resource 480 1)"
expect 200 "conditional write with the current version (a claim)"
expect_json .version 2 "version incremented"

call PUT "/v1/data/${KEY}" "$(resource 460 1)"
expect 409 "conditional write with a stale version is rejected (no double-booking)"

call PUT "/v1/data/${MISSING}" "$(resource 1 1)"
expect 412 "conditional write to a missing key"

call POST /v1/query "{\"collection\":\"resource\",\"geoCells\":[\"${CELL}\"],\"match\":{\"type\":\"WATER\",\"status\":\"AVAILABLE\"}}"
expect 200 "query by geo cell and index attributes"
expect_json "[.records[] | select(.key == \"${KEY}\")] | length" 1 "query finds the record"
expect_json "[.records[] | select(.key == \"${KEY}\")][0].value.capacity.remaining" 480 "query returns the latest value"

call DELETE "/v1/data/${KEY}?expectedVersion=1"
expect 409 "conditional delete with a stale version is rejected"

call DELETE "/v1/data/${KEY}?expectedVersion=2"
expect 204 "conditional delete with the current version"

call GET "/v1/data/${KEY}"
expect 404 "deleted record is gone"

echo "Requests are keyed by request ID only"
call PUT "/v1/data/${REQUEST_KEY}" '{"value":{"type":"WATER","status":"PENDING"},"indexes":{"collection":"request","type":"WATER","status":"PENDING","geoCell":"tdr1yz"},"expectedVersion":0}'
expect 201 "create a request under request:{requestId}"
call GET "/v1/data/${REQUEST_KEY}"
expect 200 "read the request back by ID"
expect_json .value.status PENDING "request value round-trips"

echo "Keys and indexes that would break routing are rejected"
call PUT "/v1/data/request:${CELL}:abc" '{"value":{},"indexes":{"collection":"request"}}'
expect 400 "request key with a cell segment (requests are sharded by ID)"
expect_json .error INVALID_REQUEST "rejection uses the standard error shape"
call GET "/v1/data/resource:abc-uuid-only"
expect 400 "resource key without a geohash cell"
call GET "/v1/data/resource:smoke1:abc"
expect 400 "resource key whose cell is not a geohash"
call PUT "/v1/data/resource:${CELL}:abc" '{"value":{},"indexes":{"collection":"resource","geoCell":"tdr1yy"}}'
expect 400 "resource indexed under a different cell than its key"
call PUT "/v1/data/request:abc" '{"value":{},"indexes":{"collection":"resource"}}'
expect 400 "indexes.collection must match the key"

echo "PASS: storage smoke test"
