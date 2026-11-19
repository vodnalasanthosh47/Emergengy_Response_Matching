#!/usr/bin/env bash
# Kubernetes scaling (PDF §9.2 "microservice scaling", §10 "Kubernetes Scaling", §11):
# increase the number of pods of a service while clients keep calling it, show that the
# new pods take part of the traffic, then scale back down. No call may fail.
#
#   1. Scale SERVICE (default request-service) from its current 2 pods to REPLICAS (4).
#   2. The gateway starts sending calls to the new pods as soon as they are ready:
#      each pod's own request counter (Spring Boot metrics) shows it served calls.
#   3. Scale back to the original count; the removed pods finish their calls first.
#
# Usage:  make k8s-forward   (another terminal), then  tests/e2e/k8s-scaling.sh
#         SERVICE=matching-service REPLICAS=3 tests/e2e/k8s-scaling.sh
# Needs:  bash 4+, curl, kubectl pointing at the cluster (`make k8s-up`).
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
NAMESPACE="${NAMESPACE:-emergency}"
SERVICE="${SERVICE:-request-service}"
REPLICAS="${REPLICAS:-4}"
TMP="$(mktemp -d)"
ORIGINAL=""
cleanup() {
  touch "$TMP/stop"
  wait 2>/dev/null || true
  # Leave the Deployment as it was, even if the test failed half-way.
  [[ -n "$ORIGINAL" ]] && kubectl -n "$NAMESPACE" scale deploy "$SERVICE" --replicas="$ORIGINAL" >/dev/null 2>&1
  rm -rf "${TMP:?}"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }
k() { kubectl -n "$NAMESPACE" "$@"; }

pods() { k get pods -l "app.kubernetes.io/name=$SERVICE" -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'; }
ready_replicas() { k get deploy "$SERVICE" -o jsonpath='{.status.readyReplicas}'; }
# Calls this pod has answered on the service's API (not health checks), from its own
# Spring Boot metrics.
served() {
  k exec "$1" -- curl -s localhost:8080/actuator/prometheus |
    awk '/^http_server_requests_seconds_count/ && !/uri="\/actuator/ { n += $NF } END { printf "%d", n }'
}
wait_for() { # wait_for DESCRIPTION SECONDS COMMAND...
  local what="$1" limit="$2"
  shift 2
  for ((i = 0; i < limit; i++)); do "$@" && return 0; sleep 1; done
  fail "timed out after ${limit}s: $what"
}

case "$SERVICE" in
  request-service) METHOD=GET URL="$BASE_URL/requests/00000000-0000-4000-8000-000000000000" ;;
  resource-service) METHOD=GET URL="$BASE_URL/resources?type=WATER&limit=1" ;;
  matching-service) METHOD=POST URL="$BASE_URL/match/00000000-0000-4000-8000-000000000000" ;;
  *) fail "unknown SERVICE $SERVICE" ;;
esac

# Call the service continuously until $TMP/stop exists. A 4xx is a real answer; a 5xx
# or no answer ("000") is a failed call.
load() {
  while [[ ! -e "$TMP/stop" ]]; do
    curl -s -o /dev/null -w '%{http_code}\n' --max-time 5 -X "$METHOD" "$URL" >>"$TMP/codes" || true
    sleep 0.05
  done
}

echo "Kubernetes scaling test: $SERVICE → $REPLICAS pods (gateway ${BASE_URL})"
curl -sf "$BASE_URL/healthz" >/dev/null || fail "gateway not reachable at $BASE_URL (run make k8s-forward)"
ORIGINAL="$(k get deploy "$SERVICE" -o jsonpath='{.spec.replicas}')"
((REPLICAS > ORIGINAL)) || fail "REPLICAS ($REPLICAS) must be more than the current $ORIGINAL"
[[ "$(ready_replicas)" == "$ORIGINAL" ]] || fail "$SERVICE is not fully ready before the test"
mapfile -t before < <(pods)
echo "  before: ${#before[*]} pods: ${before[*]}"

: >"$TMP/codes"
load &
sleep 2

echo
echo "== 1. Scale up: kubectl scale deploy/$SERVICE --replicas=$REPLICAS"
started=$SECONDS
k scale deploy "$SERVICE" --replicas="$REPLICAS" >/dev/null
all_ready() { [[ "$(ready_replicas)" == "$REPLICAS" ]]; }
wait_for "$REPLICAS ready pods" 240 all_ready
ok "$REPLICAS pods ready after $((SECONDS - started))s"

echo
echo "== 2. The new pods take part of the traffic"
sleep 8 # let the gateway pick up the new endpoints and send them calls
mapfile -t after < <(pods)
new=()
for p in "${after[@]}"; do
  n="$(served "$p")"
  if printf '%s\n' "${before[@]}" | grep -q -x -F "$p"; then
    echo "      $p  (existing)  served $n calls in total"
  else
    echo "      $p  (new)       served $n calls"
    ((n > 0)) || fail "new pod $p received no calls"
    new+=("$p")
  fi
done
((${#new[*]} == REPLICAS - ORIGINAL)) || fail "expected $((REPLICAS - ORIGINAL)) new pods, found ${#new[*]}"
ok "every new pod is serving calls through the gateway"

echo
echo "== 3. Scale back down to $ORIGINAL"
started=$SECONDS
k scale deploy "$SERVICE" --replicas="$ORIGINAL" >/dev/null
gone() { [[ "$(pods | wc -l | tr -d ' ')" == "$ORIGINAL" && "$(ready_replicas)" == "$ORIGINAL" ]]; }
wait_for "back to $ORIGINAL pods" 120 gone
ok "back to $ORIGINAL pods after $((SECONDS - started))s"
sleep 2

touch "$TMP/stop"
wait
total=$(wc -l <"$TMP/codes" | tr -d ' ')
failed=$(grep -c -E '^(5|000)' "$TMP/codes" || true)
((total >= 50)) || fail "only $total calls were made"
((failed == 0)) || fail "$failed of $total calls failed while scaling ($(sort "$TMP/codes" | uniq -c | tr '\n' ' '))"
ok "$total calls during scale-up and scale-down, none failed"
ORIGINAL="" # already restored

echo
echo "PASS: Kubernetes scaling test"
