#!/usr/bin/env bash
# The final demonstration (PDF §10), step by step, on the Kubernetes deployment:
#
#    0. What runs: Deployments, StatefulSets, Services, the gateway
#    1. Request creation and retrieval
#    2. Resource registration
#    3. Resource matching (asynchronous via RabbitMQ, and POST /match/{id})
#    4. Data distribution across the three shards
#    5. Asynchronous processing: many requests at once through RabbitMQ
#    6. Storage failure: delete a shard's primary pod, keep working on the replica
#    7. Node recovery: the pod comes back and is synchronised
#    8. Kubernetes recovery: terminate an application pod
#    9. Kubernetes scaling: more pods, still available
#
# It pauses before each step (press Enter), so it can be presented live. Every call goes
# through the gateway like a real client, except the storage topology (coordinator).
#
# Usage:  make k8s-up, make k8s-forward (another terminal), then  scripts/demo.sh
#         DEMO_PAUSE=0 scripts/demo.sh     runs straight through (no pauses)
#         DEMO_FROM=6 scripts/demo.sh      starts at step 6
# Needs:  bash 4+, curl, jq, python3, kubectl.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
COORDINATOR_URL="${COORDINATOR_URL:-http://localhost:9090}"
RABBIT_API="${RABBIT_API:-http://localhost:15672/api}"
RABBIT_AUTH="${RABBIT_AUTH:-emergency:emergency}"
NAMESPACE="${NAMESPACE:-emergency}"
DEMO_PAUSE="${DEMO_PAUSE:-1}"
DEMO_FROM="${DEMO_FROM:-0}"

if [[ -t 1 ]]; then B=$'\e[1m' D=$'\e[2m' G=$'\e[32m' R=$'\e[31m' C=$'\e[36m' N=$'\e[0m'; else B='' D='' G='' R='' C='' N=''; fi

k() { kubectl -n "$NAMESPACE" "$@"; }
fail() { echo "${R}FAIL: $*${N}" >&2; exit 1; }
say() { echo "  $*"; }
good() { echo "  ${G}✔ $*${N}"; }
cmd() { echo "  ${C}\$ $*${N}"; } # show a command before running it

STEP=-1
step() { # step NUMBER TITLE — returns 1 (skip) for steps before DEMO_FROM
  STEP="$1"
  ((STEP >= DEMO_FROM)) || return 1
  echo
  echo "${B}━━ Step $1. $2 ━━${N}"
  if [[ "$DEMO_PAUSE" == 1 ]]; then read -r -p "  ${D}(Enter to run)${N}" _ </dev/tty; fi
  return 0
}

# api METHOD PATH [JSON] — call the gateway; body in $BODY, status in $STATUS.
api() {
  local args=(-s -o "$TMP/body" -w '%{http_code}' -X "$1" "$BASE_URL$2")
  [[ $# -ge 3 ]] && args+=(-H 'Content-Type: application/json' -d "$3")
  STATUS="$(curl "${args[@]}")" || fail "cannot reach $BASE_URL$2"
  BODY="$(cat "$TMP/body")"
}
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP:?}"' EXIT
# show METHOD PATH [JSON] — print the call, make it, print the answer.
show() {
  cmd "curl -X $1 $BASE_URL$2${3:+ -d '$3'}"
  api "$@"
  echo "  ${D}→ HTTP $STATUS${N}"
  jq -C . <<<"$BODY" 2>/dev/null | sed 's/^/    /' || echo "    $BODY"
}
field() { jq -r "$1" <<<"$BODY"; }

# Which shard the coordinator puts a key on (ADR 0006): requests by request ID,
# resources by geohash cell, crc32(token) mod 3.
shard_of() { python3 -c 'import sys, zlib; print(zlib.crc32(sys.argv[1].encode()) % 3)' "$1"; }
topology() {
  curl -sS "$COORDINATOR_URL/internal/topology" |
    jq -r '.shards[] | "    shard \(.shard)  primary=\(.primary // "NONE")", (.nodes[] | "      \(.nodeId)  \(.role)\t\(.state)\trecords=\(.records // "-")")'
}
primary_of() { curl -sS "$COORDINATOR_URL/internal/topology" | jq -r --argjson s "$1" '.shards[] | select(.shard == $s) | .primary // "NONE"'; }
node_info() { # node_info NODE → "ROLE STATE RECORDS"
  curl -sS "$COORDINATOR_URL/internal/topology" |
    jq -r --arg n "$1" '.shards[].nodes[] | select(.nodeId == $n) | "\(.role) \(.state) \(.records // 0)"'
}
wait_status() { # wait_status REQUEST_ID → final status (MATCHED/UNMATCHED) in $BODY's .status
  for ((i = 0; i < 40; i++)); do
    api GET "/requests/$1"
    [[ "$(field .status)" =~ ^(MATCHED|UNMATCHED)$ ]] && return 0
    sleep 0.5
  done
  fail "request $1 stayed $(field .status)"
}
# A random spot near Bengaluru for this run, so resources from earlier runs are not
# candidates (matching only looks in the request's geohash cell and its neighbours).
LAT="$(awk -v r="$RANDOM" 'BEGIN { printf "%.4f", 12.85 + r / 32767 * 0.25 }')"
LNG="$(awk -v r="$RANDOM" 'BEGIN { printf "%.4f", 77.50 + r / 32767 * 0.25 }')"
near() { awk -v a="$1" -v d="$2" 'BEGIN { printf "%.4f", a + d }'; }

echo "${B}Distributed Emergency Resource Matching — demo${N}"
echo "  gateway $BASE_URL   coordinator $COORDINATOR_URL   namespace $NAMESPACE"
curl -sf "$BASE_URL/healthz" >/dev/null || fail "gateway not reachable at $BASE_URL (run make k8s-forward)"
curl -sf "$COORDINATOR_URL/internal/topology" >/dev/null || fail "coordinator not reachable at $COORDINATOR_URL"

# ---------------------------------------------------------------------------------------
if step 0 "What runs on Kubernetes"; then
  cmd "kubectl -n $NAMESPACE get deploy,statefulset"
  k get deploy,statefulset | sed 's/^/    /'
  cmd "kubectl -n $NAMESPACE get pods"
  k get pods -o custom-columns=POD:.metadata.name,READY:.status.containerStatuses[*].ready,RESTARTS:.status.containerStatuses[0].restartCount,NODE-IP:.status.podIP | sed 's/^/    /'
  cmd "kubectl -n $NAMESPACE get gateway,httproute"
  k get gateway,httproute | sed 's/^/    /'
  say "Clients only see the gateway. Services find the coordinator and RabbitMQ by their"
  say "Kubernetes Service names; only the coordinator talks to the storage pods."
fi

# ---------------------------------------------------------------------------------------
if step 1 "Request creation and retrieval"; then
  say "A flood victim asks for shelter for 4 people. No shelter is registered here yet."
  show POST /requests "{\"type\":\"SHELTER\",\"location\":{\"lat\":$LAT,\"lng\":$LNG},\"details\":{\"people\":4},\"requester\":{\"name\":\"Asha\",\"contact\":\"+91-90000-00001\"}}"
  [[ "$STATUS" == 201 ]] || fail "expected 201"
  SHELTER_REQ="$(field .requestId)"
  good "stored with status $(field .status); geoCell $(field .geoCell) computed by the server"
  say "Read it back (the Request Service published RequestCreated; matching found nothing):"
  wait_status "$SHELTER_REQ"
  show GET "/requests/$SHELTER_REQ"
  good "status $(field .status): no shelter within the request's cell and its 8 neighbours"
fi

# ---------------------------------------------------------------------------------------
if step 2 "Resource registration"; then
  say "An NGO registers 500 L of drinking water close by, and a school offers shelter."
  show POST /resources "{\"type\":\"WATER\",\"location\":{\"lat\":$(near "$LAT" 0.002),\"lng\":$(near "$LNG" 0.002)},\"provider\":{\"name\":\"Akshaya NGO\",\"kind\":\"NGO\",\"contact\":\"+91-80000-00000\"},\"capacity\":{\"total\":500}}"
  [[ "$STATUS" == 201 ]] || fail "expected 201"
  WATER_RES="$(field .resourceId)"
  api POST /resources "{\"type\":\"SHELTER\",\"location\":{\"lat\":$(near "$LAT" -0.003),\"lng\":$LNG},\"provider\":{\"name\":\"Govt School\",\"kind\":\"GOVT\"},\"capacity\":{\"total\":50}}"
  [[ "$STATUS" == 201 ]] || fail "expected 201"
  SHELTER_RES="$(field .resourceId)"
  good "water $WATER_RES (500 L), shelter $SHELTER_RES (50 places)"
  show GET "/resources?type=WATER&status=AVAILABLE&limit=2"
fi

# ---------------------------------------------------------------------------------------
if step 3 "Resource matching"; then
  say "a) Asynchronous: a request for 20 L of water. The Request Service stores it and"
  say "   publishes RequestCreated; a Matching pod consumes it and claims the water."
  show POST /requests "{\"type\":\"WATER\",\"location\":{\"lat\":$LAT,\"lng\":$LNG},\"details\":{\"quantity\":20}}"
  WATER_REQ="$(field .requestId)"
  say "Immediately after creation it is $(field .status). A moment later:"
  wait_status "$WATER_REQ"
  show GET "/requests/$WATER_REQ"
  [[ "$(field .status)" == MATCHED ]] || fail "expected MATCHED"
  show GET "/resources/${WATER_RES:-$(field .assignedResourceId)}"
  good "MATCHED to $(field .resourceId); capacity $(field .capacity.remaining) of $(field .capacity.total) L left"
  cmd "kubectl -n $NAMESPACE logs -l app.kubernetes.io/name=matching-service --prefix | grep $WATER_REQ"
  k logs -l app.kubernetes.io/name=matching-service --prefix --since=5m | grep "$WATER_REQ" | sed -E 's/^\[pod\/([^/]+)\/[^]]+\] [^ ]+ +INFO 1 --- \[[^]]+\] \[[^]]+\] [^:]+: /    \1: /' || true
  if [[ -n "${SHELTER_REQ:-}" ]]; then
    echo
    say "b) Synchronous: the shelter request from step 1 is UNMATCHED; now that a shelter"
    say "   exists, match it on demand:"
    show POST "/match/$SHELTER_REQ"
    [[ "$STATUS" == 200 ]] || fail "expected 200"
    good "assigned $(field .resourceId), $(field .distanceKm) km away"
    say "Matching it again is refused, so a request is never served twice:"
    show POST "/match/$SHELTER_REQ"
  fi
fi

# ---------------------------------------------------------------------------------------
if step 4 "Data distribution across the three shards"; then
  say "The coordinator picks the shard from the key alone: requests by crc32(requestId) mod 3,"
  say "resources by crc32(geohash cell) mod 3, so nearby resources live together."
  say "Records per shard before:"
  topology
  say "Register water in six cities and create a request in each:"
  printf '    %-10s %-8s %-15s %-38s %s\n' city cell "resource shard" request "request shard"
  for city in "Bengaluru 12.9716 77.5946" "Chennai 13.0827 80.2707" "Mumbai 19.0760 72.8777" \
    "Delhi 28.6139 77.2090" "Kolkata 22.5726 88.3639" "Hyderabad 17.3850 78.4867"; do
    read -r name lat lng <<<"$city"
    api POST /resources "{\"type\":\"FOOD\",\"location\":{\"lat\":$lat,\"lng\":$lng},\"provider\":{\"name\":\"Demo $name\",\"kind\":\"GOVT\"},\"capacity\":{\"total\":10}}"
    [[ "$STATUS" == 201 ]] || fail "resource in $name: HTTP $STATUS"
    cell="$(field .geoCell)"
    api POST /requests "{\"type\":\"MEDICINE\",\"location\":{\"lat\":$lat,\"lng\":$lng},\"details\":{\"name\":\"Demo\",\"quantity\":1}}"
    [[ "$STATUS" == 201 ]] || fail "request in $name: HTTP $STATUS"
    id="$(field .requestId)"
    printf '    %-10s %-8s %-15s %-38s %s\n' "$name" "$cell" "$(shard_of "$cell")" "$id" "$(shard_of "$id")"
  done
  sleep 1
  say "Records per shard after (each shard's two pods hold the same copy):"
  topology
  good "records spread over shards 0, 1 and 2; a client never needs to know where"
fi

# ---------------------------------------------------------------------------------------
if step 5 "Asynchronous processing: many requests at once"; then
  say "100 meals are registered; 8 requests for 20 meals each arrive at the same moment."
  say "Only 5 can be served. Two Matching pods consume the queue in parallel."
  api POST /resources "{\"type\":\"FOOD\",\"location\":{\"lat\":$(near "$LAT" 0.001),\"lng\":$(near "$LNG" -0.001)},\"provider\":{\"name\":\"Community kitchen\",\"kind\":\"VOLUNTEER\"},\"capacity\":{\"total\":100}}"
  FOOD_RES="$(field .resourceId)"
  before="$(curl -s -u "$RABBIT_AUTH" "$RABBIT_API/queues/%2F/matching.request-created" | jq '.message_stats.ack // 0')"
  ids=()
  for i in $(seq 1 8); do
    curl -s -X POST "$BASE_URL/requests" -H 'Content-Type: application/json' \
      -d "{\"type\":\"FOOD\",\"location\":{\"lat\":$LAT,\"lng\":$LNG},\"details\":{\"quantity\":20}}" >"$TMP/req.$i" &
  done
  wait
  for i in $(seq 1 8); do ids+=("$(jq -r .requestId "$TMP/req.$i")"); done
  matched=0 unmatched=0
  for id in "${ids[@]}"; do
    wait_status "$id"
    s="$(field .status)"
    [[ "$s" == MATCHED ]] && matched=$((matched + 1)) || unmatched=$((unmatched + 1))
    say "  request $id  $s"
  done
  api GET "/resources/$FOOD_RES"
  left="$(field .capacity.remaining)"
  # RabbitMQ refreshes its counters every few seconds.
  for ((i = 0; i < 15; i++)); do
    after="$(curl -s -u "$RABBIT_AUTH" "$RABBIT_API/queues/%2F/matching.request-created" | jq '.message_stats.ack // 0')"
    ((after - before >= 8)) && break
    sleep 1
  done
  say "Which Matching pod handled each event:"
  for id in "${ids[@]}"; do
    k logs -l app.kubernetes.io/name=matching-service --prefix --since=5m | grep "RequestCreated $id handled" |
      sed -E 's/^\[pod\/([^/]+)\/[^]]+\].*RequestCreated ([0-9a-f-]+) handled: (.*)$/      \1  \2  \3/' || true
  done
  cmd "RabbitMQ queue matching.request-created"
  curl -s -u "$RABBIT_AUTH" "$RABBIT_API/queues/%2F/matching.request-created" |
    jq -r '"    consumers=\(.consumers)  waiting=\(.messages)  acked so far=\(.message_stats.ack // 0)"'
  ((matched == 5 && unmatched == 3)) || fail "expected 5 MATCHED and 3 UNMATCHED, got $matched / $unmatched"
  [[ "$left" == 0 ]] || fail "expected 0 meals left, got $left"
  good "$matched MATCHED, $unmatched UNMATCHED, 0 meals left: no double booking; $((after - before)) events acknowledged"
fi

# ---------------------------------------------------------------------------------------
if step 6 "Storage failure: a shard's primary pod disappears"; then
  [[ -n "${WATER_REQ:-}" ]] || {
    api POST /requests "{\"type\":\"WATER\",\"location\":{\"lat\":$LAT,\"lng\":$LNG},\"details\":{\"quantity\":1}}"
    WATER_REQ="$(field .requestId)"
  }
  SHARD="$(shard_of "$WATER_REQ")"
  VICTIM="$(primary_of "$SHARD")"
  say "The water request from step 3 is on shard $SHARD, whose primary is $VICTIM."
  topology
  VICTIM_UID="$(k get pod "$VICTIM" -o jsonpath='{.metadata.uid}')"
  cmd "kubectl -n $NAMESPACE delete pod $VICTIM"
  k delete pod "$VICTIM" --wait=false | sed 's/^/    /'
  started=$SECONDS
  cmd "kubectl -n $NAMESPACE get pods -l emergency/shard=$SHARD"
  k get pods -l "emergency/shard=$SHARD" | sed 's/^/    /'
  for ((i = 0; i < 60; i++)); do
    p="$(primary_of "$SHARD")"
    [[ "$p" != "$VICTIM" && "$p" != NONE ]] && break
    sleep 1
  done
  [[ "$p" != "$VICTIM" && "$p" != NONE ]] || fail "no new primary for shard $SHARD"
  good "the coordinator promoted the replica $p to primary after $((SECONDS - started))s"
  topology
  say "Reads and writes keep working:"
  show GET "/requests/$WATER_REQ"
  [[ "$STATUS" == 200 ]] || fail "read failed during the failure"
  writes=0
  for i in 1 2 3 4 5 6; do
    api POST /requests "{\"type\":\"SHELTER\",\"location\":{\"lat\":$LAT,\"lng\":$LNG},\"details\":{\"people\":1}}"
    [[ "$STATUS" == 201 ]] && writes=$((writes + 1))
    [[ "$(shard_of "$(field .requestId)")" == "$SHARD" ]] && ON_FAILED_SHARD="$(field .requestId)"
  done
  ((writes == 6)) || fail "only $writes of 6 writes succeeded during the failure"
  good "6 of 6 new requests stored during the failure${ON_FAILED_SHARD:+ (e.g. $ON_FAILED_SHARD on shard $SHARD)}"
fi

# ---------------------------------------------------------------------------------------
if step 7 "Node recovery: the pod comes back and catches up"; then
  : "${VICTIM:?run step 6 first}"
  say "The StatefulSet recreates $VICTIM with the same name and volume. It re-registers,"
  say "copies what it missed from the new primary, and becomes a replica:"
  # Wait for the NEW pod (another UID than the deleted one) to be ready, then for the
  # coordinator to list the node as ACTIVE again.
  for ((i = 0; i < 180; i++)); do
    uid="$(k get pod "$VICTIM" -o jsonpath='{.metadata.uid}' 2>/dev/null || true)"
    ready="$(k get pod "$VICTIM" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)"
    [[ -n "$uid" && "$uid" != "${VICTIM_UID:-}" && "$ready" == True ]] && break
    sleep 1
  done
  [[ "$ready" == True && "$uid" != "${VICTIM_UID:-}" ]] || fail "$VICTIM was not recreated"
  cmd "kubectl -n $NAMESPACE get pod $VICTIM"
  k get pod "$VICTIM" | sed 's/^/    /'
  for ((i = 0; i < 120; i++)); do
    read -r role state recs <<<"$(node_info "$VICTIM")"
    [[ "$state" == ACTIVE ]] && break
    sleep 1
  done
  [[ "${state:-}" == ACTIVE ]] || fail "$VICTIM did not come back ACTIVE"
  good "a new $VICTIM pod (same name, same volume) re-registered and is $role, $state"
  topology
  read -r _ _ mine <<<"$(node_info "$VICTIM")"
  read -r _ _ theirs <<<"$(node_info "$(primary_of "$SHARD")")"
  [[ "$mine" == "$theirs" ]] || fail "record counts differ: $mine vs $theirs"
  good "both copies of shard $SHARD hold $mine records, including the writes made while it was down"
fi

# ---------------------------------------------------------------------------------------
if step 8 "Kubernetes recovery: terminate an application pod"; then
  victim="$(k get pods -l app.kubernetes.io/name=request-service -o jsonpath='{.items[0].metadata.name}')"
  cmd "kubectl -n $NAMESPACE delete pod $victim"
  k delete pod "$victim" --wait=false | sed 's/^/    /'
  ok=0 failed=0 started=$SECONDS
  # Keep calling the Request Service while Kubernetes replaces the pod.
  while ((SECONDS - started < 60)); do
    code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/requests/${WATER_REQ:-00000000-0000-4000-8000-000000000000}")"
    [[ "$code" =~ ^(200|404)$ ]] && ok=$((ok + 1)) || failed=$((failed + 1))
    if ! k get pod "$victim" >/dev/null 2>&1 && [[ "$(k get deploy request-service -o jsonpath='{.status.readyReplicas}')" == 2 ]]; then break; fi
    sleep 0.1
  done
  k get pods -l app.kubernetes.io/name=request-service -o custom-columns=POD:.metadata.name,READY:.status.containerStatuses[0].ready,STARTED:.status.startTime | sed 's/^/    /'
  ((failed == 0)) || fail "$failed calls failed"
  good "replacement pod ready after $((SECONDS - started))s; $ok calls during the replacement, none failed"
fi

# ---------------------------------------------------------------------------------------
if step 9 "Kubernetes scaling: more Request Service pods"; then
  cmd "kubectl -n $NAMESPACE scale deploy/request-service --replicas=4"
  k scale deploy/request-service --replicas=4 | sed 's/^/    /'
  ok=0 failed=0 started=$SECONDS
  while ((SECONDS - started < 120)); do
    code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/requests/${WATER_REQ:-00000000-0000-4000-8000-000000000000}")"
    [[ "$code" =~ ^(200|404)$ ]] && ok=$((ok + 1)) || failed=$((failed + 1))
    [[ "$(k get deploy request-service -o jsonpath='{.status.readyReplicas}')" == 4 ]] && break
    sleep 0.1
  done
  k get pods -l app.kubernetes.io/name=request-service -o custom-columns=POD:.metadata.name,READY:.status.containerStatuses[0].ready,STARTED:.status.startTime | sed 's/^/    /'
  [[ "$(k get deploy request-service -o jsonpath='{.status.readyReplicas}')" == 4 ]] || fail "not 4 ready pods"
  good "4 pods ready after $((SECONDS - started))s; $ok calls meanwhile, $failed failed"
  say "Spread 40 calls and count per pod (each pod's own metrics):"
  sleep 3
  declare -A before_calls=()
  for p in $(k get pods -l app.kubernetes.io/name=request-service -o name); do
    before_calls[$p]="$(k exec "${p#pod/}" -- curl -s localhost:8080/actuator/prometheus | awk '/^http_server_requests_seconds_count/ && !/uri="\/actuator/ { n += $NF } END { printf "%d", n }')"
  done
  for i in $(seq 1 40); do curl -s -o /dev/null "$BASE_URL/requests/${WATER_REQ:-00000000-0000-4000-8000-000000000000}"; done
  for p in $(k get pods -l app.kubernetes.io/name=request-service -o name); do
    n="$(k exec "${p#pod/}" -- curl -s localhost:8080/actuator/prometheus | awk '/^http_server_requests_seconds_count/ && !/uri="\/actuator/ { n += $NF } END { printf "%d", n }')"
    say "  ${p#pod/}  served $((n - ${before_calls[$p]:-0})) of the 40"
  done
  cmd "kubectl -n $NAMESPACE scale deploy/request-service --replicas=2"
  k scale deploy/request-service --replicas=2 | sed 's/^/    /'
  ((failed == 0)) || fail "$failed calls failed while scaling"
  good "scaled up with no failed calls, and back to 2 pods"
fi

echo
echo "${G}${B}Demo complete.${N}"
