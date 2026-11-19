#!/usr/bin/env bash
# Makes the Kubernetes deployment reachable on the same local ports as `make up-dev`, so
# every tests/e2e script runs unchanged against Compose or Kubernetes:
#
#   http://127.0.0.1:8080   the gateway (Envoy, Gateway API)      — the clients' entry point
#   http://127.0.0.1:9090   the storage coordinator               — tests and `make topology` only
#   http://127.0.0.1:15672  RabbitMQ management UI and HTTP API   — tests and the demo only
#
# Runs until Ctrl-C. Each forward is restarted if it drops: `kubectl port-forward`
# connects to ONE pod, so it ends when that pod is deleted (e.g. a coordinator restart).
#
# Usage:  scripts/k8s-port-forward.sh      (or `make k8s-forward`)
set -uo pipefail

NAMESPACE="${NAMESPACE:-emergency}"

# Envoy Gateway runs the proxy for our Gateway in its own namespace and names the
# Service after a hash, so look it up by the labels it puts on it.
gateway_service() {
  kubectl -n envoy-gateway-system get service -o name \
    -l "gateway.envoyproxy.io/owning-gateway-namespace=${NAMESPACE},gateway.envoyproxy.io/owning-gateway-name=gateway" |
    head -n 1
}

# forward NAMESPACE TARGET LOCAL_PORT:REMOTE_PORT — keep one port-forward alive.
forward() {
  while true; do
    kubectl -n "$1" port-forward --address 127.0.0.1 "$2" "$3" >/dev/null 2>&1
    sleep 1
  done
}

GATEWAY="$(gateway_service)"
[[ -n "$GATEWAY" ]] || { echo "no Envoy service for gateway ${NAMESPACE}/gateway; run make k8s-up first" >&2; exit 1; }

trap 'kill 0' EXIT INT TERM # stop every forward on exit
forward envoy-gateway-system "$GATEWAY" 8080:80 &
forward "$NAMESPACE" service/coordinator 9090:8080 &
forward "$NAMESPACE" service/rabbitmq 15672:15672 &

echo "gateway      http://127.0.0.1:8080"
echo "coordinator  http://127.0.0.1:9090"
echo "rabbitmq UI  http://127.0.0.1:15672"
echo "Ctrl-C to stop"
wait
