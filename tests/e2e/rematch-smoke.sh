#!/usr/bin/env bash
# Rematch sweeper end-to-end test through the gateway, against the real cluster:
#
#   A. A request with no resource nearby becomes UNMATCHED.
#   B. A resource is registered afterwards. Nobody calls POST /match and no new event is
#      published for the request: the rematch sweeper re-queues it on its next pass and
#      it becomes MATCHED (within REMATCH_INTERVAL, default 2 min, plus a few seconds).
#   C. The resource was claimed exactly once.
#
# Slow by design (it waits for the timer), so it is not part of `make smoke`. Start the
# stack with a short interval to run it quickly:
#     REMATCH_INTERVAL=15s make up-dev && make rematch-smoke
#
# Usage:  BASE_URL=http://localhost:8080 REMATCH_WAIT=300 tests/e2e/rematch-smoke.sh
# Needs:  bash 4+, curl, jq.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
# How long to wait for the sweeper: one interval until the next pass, plus slack.
REMATCH_WAIT="${REMATCH_WAIT:-300}"
# A random spot in Karnataka, away from the other smoke tests' locations, so resources
# they leave behind can never be candidates.
LAT="$(printf '15.%04d' $(( RANDOM % 9000 + 500 )))"
LNG="$(printf '75.%04d' $(( RANDOM % 9000 + 500 )))"

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
status_of() { curl -s "${BASE_URL}/requests/$1" | jq -r .status; }

echo "Rematch smoke test against ${BASE_URL} (location ${LAT}, ${LNG}; waiting up to ${REMATCH_WAIT}s)"
call GET /healthz
[[ "$STATUS" == 200 ]] || fail "gateway not up (HTTP $STATUS)"

step "A. A request with nothing nearby becomes UNMATCHED"
call POST /requests "{\"type\":\"WATER\",\"location\":{\"lat\":$LAT,\"lng\":$LNG},\"details\":{\"quantity\":30}}"
[[ "$STATUS" == 201 ]] || fail "create request: HTTP $STATUS $(cat "$BODY")"
REQ="$(field .requestId)"
start=$SECONDS
until [[ "$(status_of "$REQ")" == UNMATCHED ]]; do
  (( SECONDS - start > 30 )) && fail "request $REQ is $(status_of "$REQ"), expected UNMATCHED"
  sleep 0.5
done
ok "request $REQ is UNMATCHED"

step "B. A resource appears later; the sweeper matches the request on its own"
call POST /resources "{\"type\":\"WATER\",\"location\":{\"lat\":$LAT,\"lng\":$LNG},\"provider\":{\"name\":\"Rematch smoke\",\"kind\":\"NGO\"},\"capacity\":{\"total\":100}}"
[[ "$STATUS" == 201 ]] || fail "register resource: HTTP $STATUS $(cat "$BODY")"
RES="$(field .resourceId)"
ok "registered WATER resource $RES (100 L), no POST /match"
start=$SECONDS
while [[ "$(status_of "$REQ")" != MATCHED ]]; do
  (( SECONDS - start > REMATCH_WAIT )) && fail "request $REQ still $(status_of "$REQ") after ${REMATCH_WAIT}s: is the sweeper running?"
  sleep 1
done
ok "request MATCHED by the sweeper after $(( SECONDS - start ))s"

step "C. Claimed exactly once"
call GET "/requests/$REQ"
[[ "$(field .assignedResourceId)" == "$RES" ]] || fail "assigned to $(field .assignedResourceId), expected $RES"
ok "assignedResourceId = $RES"
call GET "/resources/$RES"
[[ "$(field .capacity.remaining)" == 70 ]] || fail "resource remaining $(field .capacity.remaining), expected 70"
ok "resource remaining = 70"

echo
echo "PASS: rematch smoke test"
