#!/usr/bin/env bash
# Storage-tier resilience test (spec §2.2-4 "no double-booking", §8, §11 "multiple
# simultaneous requests"; CP1 DoD "data survives restarts"). Runs against the Compose
# stack started with `make up-dev`:
#
#   1. Concurrent claims: 20 matchers claim one resource from the same version at the
#      same moment → exactly one wins, 19 get 409. Same for 20 concurrent creates.
#   2. A primary's MongoDB dies while its storage-node process keeps running → the
#      first write may get 503, the coordinator demotes that node, retries succeed on
#      the promoted replica; when MongoDB is back the node resyncs and is ACTIVE again.
#   3. Coordinator restart (its node registry is in-memory soft state) → nodes
#      re-register, primaries are re-elected, no data is lost.
#   4. Restart of every container (coordinator, storage nodes, MongoDB) → no data lost.
#
# Usage:  BASE_URL=http://localhost:9090 tests/e2e/storage-resilience.sh
# Needs:  bash 4+, curl, jq, docker compose.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:9090}"
PROJECT="${COMPOSE_PROJECT:-emergency}"
RUN="$(date +%s)-${RANDOM}"
TMP="$(mktemp -d)"
KEY="" KEY2="" KEY3="" # the test records, set below

# On exit, delete the test records (they are raw records under real resource keys and
# would otherwise appear in GET /resources) and the temporary directory. A deleted record
# becomes a tombstone, which queries never return. Best effort: errors are ignored.
# ${TMP:?} aborts instead of expanding to an empty path, so only this script's own
# temporary directory can ever be removed.
cleanup() {
  local k
  for k in "$KEY" "$KEY2" "$KEY3"; do
    [[ -n "$k" ]] && curl -s -o /dev/null -X DELETE "${BASE_URL}/v1/data/${k}" || true
  done
  rm -rf -- "${TMP:?}"
}
trap cleanup EXIT

BODY_FILE="$TMP/body"
STATUS=""
call() { # call METHOD PATH [JSON] — sets $STATUS, body in $BODY_FILE
  local method=$1 path=$2 data=${3:-}
  local args=(-sS -o "$BODY_FILE" -w '%{http_code}' -X "$method" "${BASE_URL}${path}")
  [[ -n "$data" ]] && args+=(-H 'Content-Type: application/json' --data "$data")
  STATUS="$(curl "${args[@]}")" || STATUS="000"
}
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }
step() { echo; echo "== $*"; }
compose() { docker compose -p "$PROJECT" "$@"; }

topology() { curl -sS "${BASE_URL}/internal/topology"; }
primary_of() { topology | jq -r --argjson s "$1" '.shards[$s].primary // empty'; }
total_records() { topology | jq '[.shards[].nodes[] | select(.role == "PRIMARY") | .records] | add'; }
shard_healthy() { topology | jq -e --argjson s "$1" '.shards[$s].nodes | length == 2 and all(.state == "ACTIVE") and
  (map(.records) | unique | length == 1) and (map(.tombstones) | unique | length == 1)' >/dev/null; }
all_healthy() { shard_healthy 0 && shard_healthy 1 && shard_healthy 2; }
ready() { [[ "$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/readyz")" == 200 ]]; }

wait_for() { # wait_for SECONDS DESCRIPTION COMMAND...
  local secs=$1 desc=$2; shift 2
  local start=$SECONDS
  until "$@"; do
    (( SECONDS - start > secs )) && fail "timed out after ${secs}s waiting for: $desc"
    sleep 1
  done
  ok "$desc (after $(( SECONDS - start ))s)"
}

resource_doc() { # resource_doc CELL CLAIMED_BY [EXPECTED_VERSION]
  local ev=""
  [[ -n "${3:-}" ]] && ev=",\"expectedVersion\":$3"
  printf '{"value":{"type":"WATER","claimedBy":"%s"},"indexes":{"collection":"resource","type":"WATER","status":"AVAILABLE","geoCell":"%s"}%s}' \
    "$2" "$1" "$ev"
}

# race N KEY DOC_FN... — fires N PUTs at once; prints "count status" lines.
race() {
  local n=$1 key=$2 i; shift 2
  for (( i = 1; i <= n; i++ )); do
    curl -s -o /dev/null -w '%{http_code}\n' -X PUT "${BASE_URL}/v1/data/${key}" \
      -H 'Content-Type: application/json' --data "$("$@" "m$i")" > "$TMP/race.$i" &
  done
  wait
  cat "${TMP:?}"/race.* | sort | uniq -c
  rm -f -- "${TMP:?}"/race.*
}
claim_v1() { resource_doc tdr1yz "$1" 1; }
create_only() { resource_doc tdr1yz "$1" 0; }

# ---------------------------------------------------------------------------
echo "Storage resilience test against ${BASE_URL} (run ${RUN})"
ready || fail "coordinator not ready"
all_healthy || fail "cluster not healthy before the test"

step "1. Concurrent claims never double-book"
KEY="resource:tdr1yz:race-${RUN}"
call PUT "/v1/data/${KEY}" "$(resource_doc tdr1yz nobody 0)"
[[ "$STATUS" == 201 ]] || fail "create $KEY: HTTP $STATUS"
out=$(race 20 "$KEY" claim_v1); echo "$out" | sed 's/^/      /'
[[ "$(awk '$2 == 200 {print $1}' <<<"$out")" == 1 && "$(awk '$2 == 409 {print $1}' <<<"$out")" == 19 ]] ||
  fail "20 claims on version 1: want exactly one 200 and nineteen 409"
call GET "/v1/data/${KEY}"
[[ "$(jq .version "$BODY_FILE")" == 2 ]] || fail "record should be at version 2"
ok "20 simultaneous claims on version 1: exactly one won (record now version 2, claimed by $(jq -r .value.claimedBy "$BODY_FILE"))"

KEY2="resource:tdr1yz:create-${RUN}"
out=$(race 20 "$KEY2" create_only); echo "$out" | sed 's/^/      /'
[[ "$(awk '$2 == 201 {print $1}' <<<"$out")" == 1 && "$(awk '$2 == 409 {print $1}' <<<"$out")" == 19 ]] ||
  fail "20 create-only writes: want exactly one 201 and nineteen 409"
ok "20 simultaneous create-only writes of one key: exactly one created it"
wait_for 10 "primary and replica of every shard identical" all_healthy

step "2. A primary's MongoDB dies while the storage node keeps running"
SHARD=0
CELL=tdr1yy # crc32("tdr1yy") mod 3 = 0
victim=$(primary_of "$SHARD")
mongo="mongo-${victim#storage-}" # storage-0-a → mongo-0-a
echo "  stopping ${mongo} (the database of ${victim}, primary of shard ${SHARD})"
compose stop "$mongo" >/dev/null 2>&1
KEY3="resource:${CELL}:mongo-down-${RUN}"
attempts=0
until call PUT "/v1/data/${KEY3}" "$(resource_doc "$CELL" retry)"; [[ "$STATUS" == 201 || "$STATUS" == 200 ]]; do
  [[ "$STATUS" == 503 ]] || fail "write while ${mongo} is down: HTTP $STATUS, want 503 then success"
  attempts=$(( attempts + 1 ))
  (( attempts < 5 )) || fail "write never succeeded on the replica"
  sleep 1
done
ok "write succeeded after ${attempts} retry(ies) with 503 (the caller's retry signal)"
[[ "$(primary_of "$SHARD")" != "$victim" ]] || fail "${victim} should have been demoted"
ok "shard ${SHARD} primary is now $(primary_of "$SHARD")"
call GET "/v1/data/${KEY3}"
[[ "$STATUS" == 200 ]] || fail "read back: HTTP $STATUS"
compose start "$mongo" >/dev/null 2>&1
wait_for 90 "${victim} resynchronised (same records and tombstones as the new primary)" shard_healthy "$SHARD"

step "3. Coordinator restart: the in-memory registry is rebuilt from re-registrations"
before=$(total_records)
start=$SECONDS
compose restart coordinator >/dev/null 2>&1
wait_for 60 "coordinator ready again (primaries re-elected)" ready
wait_for 30 "every shard has a primary and an ACTIVE replica" all_healthy
after=$(total_records)
(( after == before )) || fail "records before restart: $before, after: $after"
for k in "$KEY" "$KEY3"; do call GET "/v1/data/$k"; [[ "$STATUS" == 200 ]] || fail "read $k after restart: HTTP $STATUS"; done
ok "all $after records still there; storage was unavailable for about $(( SECONDS - start ))s"

step "4. Restart every container (coordinator, storage nodes, MongoDB)"
compose restart >/dev/null 2>&1
wait_for 120 "coordinator ready again" ready
wait_for 60 "every shard has a primary and an ACTIVE replica" all_healthy
after=$(total_records)
(( after == before )) || fail "records before restart: $before, after: $after"
call GET "/v1/data/${KEY}"
[[ "$STATUS" == 200 && "$(jq .version "$BODY_FILE")" == 2 ]] || fail "claimed record lost or changed after restart"
ok "all $after records survived a full restart (data is on the MongoDB volumes)"

step "Cleanup"
for k in "$KEY" "$KEY2" "$KEY3"; do
  call DELETE "/v1/data/$k"
  [[ "$STATUS" == 204 ]] || fail "delete test record $k: HTTP $STATUS"
done
(( $(total_records) == before - 3 )) || fail "test records still counted after cleanup"
ok "3 test records removed; none left in any listing"

echo
echo "PASS: storage resilience test"
