#!/usr/bin/env bash
# Kubernetes recovery (PDF §9.2 "pod failure and automatic recovery", §10 "Kubernetes
# Recovery", §11): terminate running application pods and show that Kubernetes brings
# them back while clients keep being served.
#
#   1. For each service (request, resource, matching): delete one of its 2 pods while
#      a client keeps calling it through the gateway. The Deployment creates a
#      replacement; the other pod serves meanwhile, so no call fails.
#   2. Crash the JVM inside a pod (kill PID 1). The kubelet restarts the container in
#      the same pod (RESTARTS goes up); again no call fails.
#   3. End to end after all that: register a resource, create a request, and it is
#      MATCHED through RabbitMQ.
#
# Usage:  make k8s-forward   (another terminal), then  tests/e2e/k8s-pod-recovery.sh
# Needs:  bash 4+, curl, jq, kubectl pointing at the cluster (`make k8s-up`).
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
NAMESPACE="${NAMESPACE:-emergency}"
TMP="$(mktemp -d)"
trap 'touch "$TMP/stop"; wait 2>/dev/null; rm -rf "${TMP:?}"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }
k() { kubectl -n "$NAMESPACE" "$@"; }

pods_of() { k get pods -l "app.kubernetes.io/name=$1" -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'; }
ready_replicas() { k get deploy "$1" -o jsonpath='{.status.readyReplicas}'; }

# load NAME METHOD URL — call URL in a loop until $TMP/stop exists; one status per line
# in $TMP/NAME.codes ("000" = no answer at all).
load() {
  local out="$TMP/$1.codes"
  : >"$out"
  while [[ ! -e "$TMP/stop" ]]; do
    curl -s -o /dev/null -w '%{http_code}\n' --max-time 5 -X "$2" "$3" >>"$out" || true
    sleep 0.05
  done
}
start_load() { rm -f "$TMP/stop"; load "$@" & LOAD_PID=$!; }
stop_load() { touch "$TMP/stop"; wait "$LOAD_PID" 2>/dev/null || true; }
# A failed call is a 5xx or no answer. 4xx is a real answer from the service.
check_load() { # check_load NAME WHAT
  local total failed
  total=$(wc -l <"$TMP/$1.codes" | tr -d ' ')
  failed=$(grep -c -E '^(5|000)' "$TMP/$1.codes" || true)
  ((total >= 20)) || fail "$2: only $total calls were made"
  ((failed == 0)) || fail "$2: $failed of $total calls failed ($(sort "$TMP/$1.codes" | uniq -c | tr '\n' ' '))"
  ok "$2: $total calls through the gateway, none failed"
}

# A path each service answers quickly without changing data (the IDs do not exist).
probe_path() {
  case "$1" in
    request-service) echo "GET $BASE_URL/requests/00000000-0000-4000-8000-000000000000" ;;
    resource-service) echo "GET $BASE_URL/resources?type=WATER&limit=1" ;;
    matching-service) echo "POST $BASE_URL/match/00000000-0000-4000-8000-000000000000" ;;
  esac
}

echo "Kubernetes pod recovery test (namespace ${NAMESPACE}, gateway ${BASE_URL})"
curl -sf "$BASE_URL/healthz" >/dev/null || fail "gateway not reachable at $BASE_URL (run make k8s-forward)"

echo
echo "== 1. Delete a pod of each service while it is being called"
for svc in request-service resource-service matching-service; do
  want="$(k get deploy "$svc" -o jsonpath='{.spec.replicas}')"
  [[ "$(ready_replicas "$svc")" == "$want" ]] || fail "$svc is not fully ready before the test"
  victim="$(pods_of "$svc" | head -n 1)"
  read -r method url <<<"$(probe_path "$svc")"
  start_load "$svc" "$method" "$url"
  sleep 2
  echo "  deleting pod $victim ($svc has $want ready pods)"
  started=$SECONDS
  k delete pod "$victim" --wait=false >/dev/null
  # Recovered = the old pod is gone and the Deployment has all its ready pods again.
  for ((i = 0; i < 180; i++)); do
    if ! k get pod "$victim" >/dev/null 2>&1 && [[ "$(ready_replicas "$svc")" == "$want" ]]; then break; fi
    sleep 1
  done
  k get pod "$victim" >/dev/null 2>&1 && fail "$victim was not removed"
  [[ "$(ready_replicas "$svc")" == "$want" ]] || fail "$svc did not get back to $want ready pods"
  ok "$svc: replacement created and ready after $((SECONDS - started))s; pods now: $(pods_of "$svc" | tr '\n' ' ')"
  sleep 2
  stop_load
  check_load "$svc" "$svc during the pod deletion"
done

echo
echo "== 2. Crash the JVM in a matching-service pod (kill PID 1 inside the container)"
victim="$(pods_of matching-service | head -n 1)"
restarts_before="$(k get pod "$victim" -o jsonpath='{.status.containerStatuses[0].restartCount}')"
read -r method url <<<"$(probe_path matching-service)"
start_load crash "$method" "$url"
sleep 2
k exec "$victim" -- sh -c 'kill 1'
started=$SECONDS
for ((i = 0; i < 180; i++)); do
  restarts="$(k get pod "$victim" -o jsonpath='{.status.containerStatuses[0].restartCount}')"
  ready="$(k get pod "$victim" -o jsonpath='{.status.containerStatuses[0].ready}')"
  [[ "$restarts" -gt "$restarts_before" && "$ready" == true ]] && break
  sleep 1
done
[[ "$restarts" -gt "$restarts_before" ]] || fail "$victim was not restarted"
[[ "$ready" == true ]] || fail "$victim did not become ready again"
ok "$victim: container restarted by the kubelet (restarts $restarts_before → $restarts), ready after $((SECONDS - started))s"
sleep 2
stop_load
check_load crash "matching-service during the crash"

echo
echo "== 3. End to end after the recoveries: resource → request → MATCHED"
# A random spot near Bengaluru, so earlier runs' resources are not candidates.
lat="$(awk -v r="$RANDOM" 'BEGIN { printf "%.4f", 12.80 + r / 32767 * 0.3 }')"
lng="$(awk -v r="$RANDOM" 'BEGIN { printf "%.4f", 77.45 + r / 32767 * 0.3 }')"
resource="$(curl -sf -X POST "$BASE_URL/resources" -H 'Content-Type: application/json' \
  -d "{\"type\":\"WATER\",\"location\":{\"lat\":$lat,\"lng\":$lng},\"provider\":{\"name\":\"Recovery test\",\"kind\":\"OTHER\"},\"capacity\":{\"total\":100}}" |
  jq -r .resourceId)" || fail "could not register a resource"
request="$(curl -sf -X POST "$BASE_URL/requests" -H 'Content-Type: application/json' \
  -d "{\"type\":\"WATER\",\"location\":{\"lat\":$lat,\"lng\":$lng},\"details\":{\"quantity\":10}}" |
  jq -r .requestId)" || fail "could not create a request"
for ((i = 0; i < 60; i++)); do
  status="$(curl -s "$BASE_URL/requests/$request" | jq -r .status)"
  [[ "$status" == MATCHED ]] && break
  sleep 0.5
done
[[ "$status" == MATCHED ]] || fail "request $request is $status, expected MATCHED"
assigned="$(curl -s "$BASE_URL/requests/$request" | jq -r .assignedResourceId)"
[[ "$assigned" == "$resource" ]] || fail "request assigned to $assigned, expected $resource"
remaining="$(curl -s "$BASE_URL/resources/$resource" | jq -r .capacity.remaining)"
[[ "$remaining" == 90 ]] || fail "resource remaining is $remaining, expected 90"
ok "request $request MATCHED to $resource (100 → $remaining left)"

echo
k get pods -l 'app.kubernetes.io/name in (request-service,resource-service,matching-service)' \
  -o custom-columns=POD:.metadata.name,READY:.status.containerStatuses[0].ready,RESTARTS:.status.containerStatuses[0].restartCount,STARTED:.status.startTime
echo
echo "PASS: Kubernetes pod recovery test"
