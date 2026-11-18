#!/usr/bin/env bash
# Storage-tier failover test and demo (spec §8 Failure Handling, §10 Data Distribution /
# Storage Failure / Node Recovery, §11 shard selection). Runs against the Compose stack:
#
#   1. Data distribution and shard selection: write requests (all from ONE location)
#      and resources in several geohash cells. Requests are placed by a hash of their
#      ID, so they spread over all 3 shards; resources by a hash of their cell. The
#      script computes every key's shard itself (crc32 mod 3, independently of the
#      coordinator) and checks each shard gained exactly the records it should, on
#      its primary and its replica.
#   2. Storage failure: stop a shard's primary container. The coordinator detects the
#      missed heartbeats, marks it DOWN and promotes the replica. Every record stays
#      readable, and writes, updates and deletes on that shard continue.
#   3. Node recovery: start the container again. It re-registers, syncs what it missed
#      from the new primary, and becomes an ACTIVE replica with identical data.
#   4. Proof of sync: stop the other node; the recovered node now serves every record,
#      including the writes made while it was down.
#
# Usage:  BASE_URL=http://localhost:9090 tests/e2e/storage-failover.sh
#         MODE=k8s BASE_URL=http://localhost:9091 tests/e2e/storage-failover.sh
# Needs:  bash 4+, curl, jq, python3 (macOS or Linux), and either the Compose stack
#         (`make up-dev`; MODE=compose, the default) or the Kubernetes deployment
#         (`make k8s-up`, then port-forward the coordinator; MODE=k8s). In Kubernetes
#         mode a node is "stopped" by deleting its pod, and the StatefulSet recreates it
#         automatically (with its volume).

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:9090}"
PROJECT="${COMPOSE_PROJECT:-emergency}"
MODE="${MODE:-compose}"
NAMESPACE="${NAMESPACE:-emergency}"
SHARD="${SHARD:-1}" # the shard whose primary is killed
# Geohash cells (precision 6, as the Resource Service computes them). Between them
# they map to all three shards.
CELLS=(tdr1yz tdr1yy tdr1zn tdr1yv tdr1yw u4pruy te7udq ttnfv2 tsq4e7 tdr4bq)
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

# new_uuid prints a lower-case random UUID on macOS and Linux.
new_uuid() {
  if command -v uuidgen >/dev/null; then uuidgen | tr '[:upper:]' '[:lower:]'
  else cat /proc/sys/kernel/random/uuid; fi
}

# key_shard KEY prints the shard that must own KEY, computed here rather than asked
# from the coordinator: crc32(requestId) mod 3 for requests, crc32(geoCell) mod 3 for
# resources (the same rule as storage/coordinator/internal/routing).
key_shard() {
  local key=$1 token
  case "$key" in
    request:*) token=${key#request:} ;;
    resource:*) token=$(cut -d: -f2 <<<"$key") ;;
    *) fail "unexpected key $key" ;;
  esac
  python3 -c 'import sys, zlib; print(zlib.crc32(sys.argv[1].encode()) % 3)' "$token"
}

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
primary_records() { topology | jq -r --argjson s "$1" '.shards[$s].nodes[] | select(.role == "PRIMARY") | .records'; }

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

request_doc() { # request_doc CELL STATUS — the cell is searchable data, not the shard
  printf '{"value":{"type":"WATER","geoCell":"%s","status":"%s","details":{"quantity":20,"unit":"L"}},"indexes":{"collection":"request","type":"WATER","status":"%s","geoCell":"%s"}}' \
    "$1" "$2" "$2" "$1"
}
resource_doc() { # resource_doc CELL
  printf '{"value":{"type":"WATER","geoCell":"%s","status":"AVAILABLE","capacity":{"total":500,"remaining":500,"unit":"L"}},"indexes":{"collection":"resource","type":"WATER","status":"AVAILABLE","geoCell":"%s"}}' \
    "$1" "$1"
}

declare -A EXPECT    # key -> expected value.status, or DELETED
declare -A KEY_SHARD # key -> shard computed by key_shard
GAINED=(0 0 0)       # records each shard should have gained in step 1

put_new() { # put_new KEY DOC
  call PUT "/v1/data/$1" "$2"
  [[ "$STATUS" == 201 ]] || fail "create $1: HTTP $STATUS $(cat "$BODY_FILE")"
  KEY_SHARD[$1]=$(key_shard "$1")
  GAINED[${KEY_SHARD[$1]}]=$(( GAINED[${KEY_SHARD[$1]}] + 1 ))
}

write_requests() { # write_requests COUNT STATUS [CELL] — requests from one place, or random places
  local i cell key
  for (( i = 0; i < $1; i++ )); do
    cell=${3:-${CELLS[$(( RANDOM % ${#CELLS[@]} ))]}}
    key="request:$(new_uuid)"
    put_new "$key" "$(request_doc "$cell" "$2")"
    EXPECT[$key]=$2
  done
}

write_resources() { # write_resources COUNT — round-robin over CELLS
  local i cell key
  for (( i = 0; i < $1; i++ )); do
    cell=${CELLS[$(( i % ${#CELLS[@]} ))]}
    key="resource:${cell}:$(new_uuid)"
    put_new "$key" "$(resource_doc "$cell")"
    EXPECT[$key]=AVAILABLE
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

shard_requests() { # shard_requests SHARD — live request keys that route to SHARD
  local key
  for key in "${!EXPECT[@]}"; do
    [[ $key == request:* && ${EXPECT[$key]} != DELETED && ${KEY_SHARD[$key]} == "$1" ]] && echo "$key"
  done
  return 0
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
for s in 0 1 2; do copies_identical "$s" || fail "shard $s is not healthy before the test"; done

step "1. Data distribution and shard selection"
before=($(primary_records 0) $(primary_records 1) $(primary_records 2))
write_requests 60 PENDING tdr1yz # 60 requests from the same cell...
req_shards=$(for k in "${!KEY_SHARD[@]}"; do echo "${KEY_SHARD[$k]}"; done | sort -u | wc -l | tr -d ' ')
(( req_shards == 3 )) || fail "60 requests from one cell used only $req_shards shard(s): requests must be sharded by ID"
ok "60 requests from one cell were spread over all 3 shards (sharded by request ID)"
write_resources 30 # ...and 30 resources, 3 in each of 10 cells
show_topology
for s in 0 1 2; do
  gained=$(( $(primary_records "$s") - before[s] ))
  (( gained == GAINED[s] )) || fail "shard $s gained $gained records, but ${GAINED[s]} keys hash to it"
  copies_identical "$s" || fail "shard $s replica differs from its primary"
  ok "shard $s gained exactly the ${GAINED[s]} records whose key hashes to it, on primary and replica"
done
mapping=""
for cell in "${CELLS[@]}"; do mapping+="${cell}→$(key_shard "resource:${cell}:x") "; done
ok "resource cells → shard (crc32(cell) mod 3): ${mapping}"

step "2. Storage failure: stop the primary of shard ${SHARD}"
victim=$(primary_of "$SHARD")
survivor=$(other_node "$SHARD" "$victim")
echo "  stopping ${victim} (replica ${survivor} should take over)"
stop_node "$victim"
wait_for 30 "${survivor} promoted to PRIMARY" primary_is "$SHARD" "$survivor"
[[ "$MODE" == k8s ]] || [[ "$(node_field "$victim" state)" == DOWN ]] || fail "${victim} should be DOWN"
show_topology
check_all_readable "during failure, served by replicas where needed"

# Keep writing during the outage: new records, and updates and deletes of records that
# live on the failed shard.
write_requests 30 PENDING
write_resources 10
n=0
for key in $(shard_requests "$SHARD"); do
  call GET "/v1/data/${key}"
  version=$(jq .version "$BODY_FILE")
  cell=$(jq -r .value.geoCell "$BODY_FILE")
  call PUT "/v1/data/${key}" "$(request_doc "$cell" MATCHED | jq -c --argjson v "$version" '. + {expectedVersion: $v}')"
  [[ "$STATUS" == 200 ]] || fail "update $key during failure: HTTP $STATUS"
  EXPECT[$key]=MATCHED
  n=$(( n + 1 )); (( n == 15 )) && break
done
(( n == 15 )) || fail "only $n requests on shard ${SHARD} to update"
deleted=0
for key in $(shard_requests "$SHARD" | head -5); do
  call DELETE "/v1/data/${key}"
  [[ "$STATUS" == 204 ]] || fail "delete $key during failure: HTTP $STATUS"
  EXPECT[$key]=DELETED
  deleted=$(( deleted + 1 ))
done
(( deleted == 5 )) || fail "only $deleted requests on shard ${SHARD} to delete"
ok "40 creates, 15 conditional updates and 5 deletes on shard ${SHARD} succeeded during the failure"
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
