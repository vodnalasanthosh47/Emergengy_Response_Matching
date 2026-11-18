#!/usr/bin/env bash
# Storage-tier failover test and demo (spec §8 Failure Handling, §10 Data Distribution /
# Storage Failure / Node Recovery). Runs against the Compose stack:
#
#   1. Data distribution: write requests in many geohash cells; every shard gets data,
#      and each shard's replica holds exactly what its primary holds.
#   2. Storage failure: stop a shard's primary container. The coordinator detects the
#      missed heartbeats, marks it DOWN and promotes the replica. Every record stays
#      readable, and writes, updates and deletes continue.
#   3. Node recovery: start the container again. It re-registers, syncs what it missed
#      from the new primary, and becomes an ACTIVE replica with identical data.
#   4. Proof of sync: stop the other node; the recovered node now serves every record,
#      including the writes made while it was down.
#
# Usage:  BASE_URL=http://localhost:9090 tests/e2e/storage-failover.sh
#         MODE=k8s BASE_URL=http://localhost:9091 tests/e2e/storage-failover.sh
# Needs:  curl, jq, and either the Compose stack (`make up-dev`; MODE=compose, the
#         default) or the Kubernetes deployment (`make k8s-up`, then port-forward the
#         coordinator; MODE=k8s). In Kubernetes mode a node is "stopped" by deleting its
#         pod, and the StatefulSet recreates it automatically (with its volume).

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:9090}"
PROJECT="${COMPOSE_PROJECT:-emergency}"
MODE="${MODE:-compose}"
NAMESPACE="${NAMESPACE:-emergency}"
SHARD="${SHARD:-1}" # the shard whose primary is killed
CELLS=(tdr1x tdr1y tdr1z tdr1v tdr1w u4pru te7ud ttnfv tsq4e tepf1 tdr4b tdr5c)
RUN="$(date +%s)"

BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

STATUS=""
call() { # call METHOD PATH [JSON] — sets $STATUS, body in $BODY_FILE
  local method=$1 path=$2 data=${3:-}
  local args=(-sS -o "$BODY_FILE" -w '%{http_code}' -X "$method" "${BASE_URL}${path}")
  [[ -n "$data" ]] && args+=(-H 'Content-Type: application/json' --data "$data")
  STATUS="$(curl "${args[@]}")" || { echo "FAIL: cannot reach ${BASE_URL}${path}" >&2; exit 1; }
}
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }
step() { echo; echo "== $*"; }

stop_node() {
  if [[ "$MODE" == k8s ]]; then
    kubectl -n "$NAMESPACE" delete pod "$1" --wait=false >/dev/null
  else
    docker compose -p "$PROJECT" stop -t 1 "$1" >/dev/null 2>&1
  fi
}
start_node() {
  if [[ "$MODE" == k8s ]]; then
    echo "  (the StatefulSet recreates pod $1 automatically)"
  else
    docker compose -p "$PROJECT" start "$1" >/dev/null 2>&1
  fi
}

topology() { curl -sS "${BASE_URL}/internal/topology"; }
show_topology() {
  topology | jq -r '.shards[] | "  shard \(.shard)  primary=\(.primary // "NONE")\n" +
    ([.nodes[] | "    \(.nodeId)  \(.role)\t\(.state)\trecords=\(.records // "-")"] | join("\n"))'
}
node_field() { topology | jq -r --arg n "$1" --arg f "$2" '.shards[].nodes[] | select(.nodeId == $n) | .[$f] // empty'; }
primary_of() { topology | jq -r --argjson s "$1" '.shards[$s].primary // empty'; }
other_node() { topology | jq -r --argjson s "$1" --arg n "$2" '.shards[$s].nodes[] | select(.nodeId != $n) | .nodeId' | head -1; }

wait_for() { # wait_for SECONDS DESCRIPTION COMMAND... — polls until COMMAND succeeds
  local secs=$1 desc=$2; shift 2
  local start=$SECONDS
  until "$@"; do
    (( SECONDS - start > secs )) && fail "timed out after ${secs}s waiting for: $desc"
    sleep 1
  done
  ok "$desc (after $(( SECONDS - start ))s)"
}
state_is() { [[ "$(node_field "$1" state)" == "$2" ]]; }
primary_is() { [[ "$(primary_of "$1")" == "$2" ]]; }

request_doc() { # request_doc CELL STATUS
  printf '{"value":{"type":"WATER","geoCell":"%s","status":"%s","details":{"quantity":20,"unit":"L"}},"indexes":{"collection":"request","type":"WATER","status":"%s","geoCell":"%s"}}' \
    "$1" "$2" "$2" "$1"
}

declare -A EXPECT # key -> expected status, or DELETED

write_requests() { # write_requests COUNT STATUS
  local i cell key
  for (( i = 0; i < $1; i++ )); do
    cell=${CELLS[$(( RANDOM % ${#CELLS[@]} ))]}
    key="request:${cell}:$(cat /proc/sys/kernel/random/uuid)"
    call PUT "/v1/data/${key}" "$(request_doc "$cell" "$2")"
    [[ "$STATUS" == 201 ]] || fail "create $key: HTTP $STATUS $(cat "$BODY_FILE")"
    EXPECT[$key]=$2
  done
}

check_all_readable() {
  local key want got n=0
  for key in "${!EXPECT[@]}"; do
    want=${EXPECT[$key]}
    call GET "/v1/data/${key}"
    if [[ "$want" == DELETED ]]; then
      [[ "$STATUS" == 404 ]] || fail "deleted $key: HTTP $STATUS"
    else
      [[ "$STATUS" == 200 ]] || fail "read $key: HTTP $STATUS $(cat "$BODY_FILE")"
      got=$(jq -r .value.status "$BODY_FILE")
      [[ "$got" == "$want" ]] || fail "$key has status $got, want $want"
    fi
    n=$(( n + 1 ))
  done
  ok "all $n records read back correctly ($1)"
}

shard_keys() { # keys (not deleted) that route to shard $1, via the coordinator's view
  local key
  for key in "${!EXPECT[@]}"; do
    [[ ${EXPECT[$key]} == DELETED ]] && continue
    echo "$key"
  done
}

copies_identical() { # both nodes of shard $1 report the same live and tombstone counts
  topology | jq -e --argjson s "$1" '.shards[$s].nodes | length == 2 and
    (map(.records) | unique | length == 1) and (map(.tombstones) | unique | length == 1) and
    all(.state == "ACTIVE")' >/dev/null
}

# ---------------------------------------------------------------------------
echo "Storage failover test against ${BASE_URL} (run ${RUN})"
call GET /readyz
[[ "$STATUS" == 200 ]] || fail "coordinator not ready: $(cat "$BODY_FILE")"

step "1. Data distribution across 3 shards"
before=$(topology | jq '[.shards[].nodes[] | select(.role == "PRIMARY") | .records] | add')
write_requests 60 PENDING
show_topology
topology | jq -e '.shards | length == 3 and all(.primary != null)' >/dev/null || fail "not 3 shards with a primary"
for s in 0 1 2; do
  topology | jq -e --argjson s "$s" '.shards[$s].nodes | map(.records) | min > 0' >/dev/null ||
    fail "shard $s has no data"
  copies_identical "$s" || fail "shard $s replica differs from its primary"
done
after=$(topology | jq '[.shards[].nodes[] | select(.role == "PRIMARY") | .records] | add')
(( after - before == 60 )) || fail "primaries gained $(( after - before )) records, want 60 (each key on exactly one shard)"
ok "60 records spread over all 3 shards; each stored once per shard, on primary and replica"

step "2. Storage failure: stop the primary of shard ${SHARD}"
victim=$(primary_of "$SHARD")
survivor=$(other_node "$SHARD" "$victim")
echo "  stopping ${victim} (replica ${survivor} should take over)"
stop_node "$victim"
wait_for 30 "${survivor} promoted to PRIMARY" primary_is "$SHARD" "$survivor"
[[ "$MODE" == k8s ]] || [[ "$(node_field "$victim" state)" == DOWN ]] || fail "${victim} should be DOWN"
show_topology
check_all_readable "during failure, served by replicas where needed"

# Keep writing during the outage: new records, updates and a delete.
write_requests 30 PENDING
n=0
for key in $(shard_keys); do
  call GET "/v1/data/${key}"
  version=$(jq .version "$BODY_FILE")
  cell=$(cut -d: -f2 <<<"$key")
  call PUT "/v1/data/${key}" "$(request_doc "$cell" MATCHED | jq -c --argjson v "$version" '. + {expectedVersion: $v}')"
  [[ "$STATUS" == 200 ]] || fail "update $key during failure: HTTP $STATUS"
  EXPECT[$key]=MATCHED
  n=$(( n + 1 )); (( n == 20 )) && break
done
for key in $(shard_keys | head -5); do
  call DELETE "/v1/data/${key}"
  [[ "$STATUS" == 204 ]] || fail "delete $key during failure: HTTP $STATUS"
  EXPECT[$key]=DELETED
done
ok "30 creates, 20 conditional updates and 5 deletes succeeded during the failure"
check_all_readable "during failure, after writes"

step "3. Node recovery: start ${victim} again"
start_node "$victim"
wait_for 90 "${victim} re-registered, synchronised and ACTIVE" state_is "$victim" ACTIVE
[[ "$(node_field "$victim" role)" == REPLICA ]] || fail "${victim} should rejoin as a replica"
wait_for 10 "shard ${SHARD} copies identical (records and tombstones)" copies_identical "$SHARD"
show_topology

step "4. Proof of sync: stop ${survivor}; ${victim} must now serve everything"
stop_node "$survivor"
wait_for 30 "${victim} promoted back to PRIMARY" primary_is "$SHARD" "$victim"
check_all_readable "served by the recovered node, including writes made while it was down"

start_node "$survivor"
wait_for 90 "${survivor} resynchronised and ACTIVE" state_is "$survivor" ACTIVE
wait_for 10 "shard ${SHARD} copies identical again" copies_identical "$SHARD"
show_topology

echo
echo "PASS: storage failover test"
