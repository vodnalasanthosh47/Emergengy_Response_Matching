# Kubernetes deployment (Minikube)

The whole system on Kubernetes (PDF §9.2, PROJECT_SPEC §2): the storage tier, RabbitMQ,
the three Java services and a Gateway API gateway, all in namespace `emergency`.

**Status (2026-11-19):** runs on Minikube (Kubernetes v1.37, Envoy Gateway v1.9.3).
`make k8s-e2e` passes: the three service smoke tests through the gateway, storage
failover by deleting pods, pod recovery and scaling. Branch `feat/kubernetes`.

## 1. Run it

Needs Docker, `minikube` and `kubectl`. Docker Desktop must have at least ~7 GB of
memory (Settings → Resources); Minikube gets 6.8 GB of it.

```bash
make k8s-start            # Minikube: 6 CPUs, 6.8 GB (once; K8S_CPUS / K8S_MEMORY to change)
make k8s-gateway-install  # Envoy Gateway: the Gateway API controller (once per cluster)
make k8s-images           # build our 5 images and load them into Minikube
make k8s-up               # deploy everything, wait until every pod and the gateway are ready
make k8s-forward          # in its own terminal: gateway :8080, coordinator :9090, RabbitMQ UI :15672
make k8s-e2e              # smoke tests + storage failover + pod recovery + scaling
```

The port-forward uses the same ports as `make up-dev`, so every `tests/e2e` script runs
unchanged against Compose or Kubernetes (stop the Compose stack first: same ports).

Other targets: `make k8s-status` (pods, Services, volumes, routes), `make k8s-down`
(remove everything, keep the data volumes), `make k8s-clean` (also delete the data).
After changing code: `make k8s-images`, then `kubectl -n emergency rollout restart deploy/<name>`
(images are tagged `local`, so Kubernetes does not see a new version by itself).

## 2. What runs

```
                 make k8s-forward :8080
                          │
        envoy-gateway-system: Envoy proxy  (Gateway "gateway", class "emergency")
          /requests │ /resources │ /match            /healthz, 404: answered by Envoy
                    ▼            ▼          ▼
        request-service ×2   resource-service ×2   matching-service ×2     Deployments
                 │   └─ publish ─► rabbitmq-0 ─ consume ─┘                 StatefulSet + volume
                 └────────────┬──────────────────────────┘
                         coordinator ×1                                    Deployment
           storage-0-{0,1}   storage-1-{0,1}   storage-2-{0,1}             StatefulSets: node + MongoDB, volume each
```

| Folder | Objects |
|---|---|
| `namespace.yaml` | namespace `emergency` (kept by `make k8s-down`, so the volumes survive) |
| `storage/` | coordinator Deployment + Service; per shard a StatefulSet of 2 pods (storage node + MongoDB sidecar, 1 Gi volume) and a headless Service |
| `services/` | ConfigMap `services-config`; RabbitMQ StatefulSet + Service; a Deployment (2 pods) + Service per Java service |
| `gateway/` | GatewayClass, EnvoyProxy settings, Gateway, HTTPRoutes, one BackendTrafficPolicy |

The RabbitMQ user and password are in the Secret `rabbitmq-credentials`, created by
`make k8s-secrets` (part of `k8s-up`) from `deploy/compose/.env`, or the local defaults
if that file does not exist. It is never committed.

## 3. Design choices

| Choice | Why |
|---|---|
| Java services as **Deployments with 2 pods** | Stateless (all data is behind the coordinator), so any pod can serve any call; 2 pods keep a service up while one is replaced (PDF §9.2 "pod replicas") |
| **RabbitMQ as a StatefulSet** (1 pod, volume) | Spec: one broker. A StatefulSet keeps the pod name `rabbitmq-0`, and RabbitMQ names its data directory after it, so durable queues and persistent messages survive a restart |
| **Matching with 2 pods** | Safe: each message goes to one consumer (competing consumers on `matching.request-created`), and claims are compare-and-set writes, so two pods can never double-book |
| **Probes** on Spring Boot's `/actuator/health/{liveness,readiness}` | readiness = RabbitMQ and coordinator reachable → only then traffic; liveness = JVM answers, deliberately *without* RabbitMQ/storage, so an outage there does not restart every pod; a startup probe gives the JVM up to 3 min |
| **Rolling updates** `maxUnavailable: 0`, **preStop** 5 s | Never fewer than 2 ready pods during an update; a stopping pod keeps serving for 5 s while the gateway stops sending to it |
| `enableServiceLinks: false` | Otherwise Kubernetes injects `RABBITMQ_PORT=tcp://…` for the Service `rabbitmq`, which clashes with our `RABBITMQ_PORT` and confuses RabbitMQ itself |
| **Gateway API with Envoy Gateway** | Spec §2.2-3 (not the retired ingress-nginx). Routes point at Services; Envoy balances over their ready pods, so scaling needs no gateway change. Envoy Gateway is the CNCF Envoy project's implementation and installs with one manifest |
| Gateway **retries refused connections** and ejects the pod for 10 s | A crashed container stays in the Service until its readiness probe fails (≤15 s). Retrying `connect-failure` / `refused-stream` on another pod is safe even for POST (the call never reached a service). Without it 4 of 106 calls failed during a crash; with it none |
| Gateway errors as the **standard error JSON** | Envoy's own errors (404 for unknown paths, 503 "no healthy upstream") are plain text; the policy rewrites only `text/plain` errors, so the services' JSON errors pass through unchanged |
| Envoy's Service is **ClusterIP** + port-forward | Works on any Minikube without `minikube tunnel` (which needs sudo) |
| Coordinator **1 pod**, `Recreate` | Its node registry is in-memory soft state (ADR 0002); Kubernetes restarts it and the nodes re-register |

Memory (requests / limits): Java pods 384 / 512 Mi (heap = 75 % of the limit),
RabbitMQ 256 / 512 Mi, MongoDB 256 / 512 Mi, storage node and coordinator 32 / 64 Mi,
Envoy 64 / 256 Mi. All of it fits in Minikube's 6.8 GB.

## 4. Demo and tests (PDF §9.2, §10, §11)

| PDF item | How | Result on Minikube |
|---|---|---|
| Deployments, Services, pod replicas | `make k8s-status` | 3 Deployments × 2 pods, StatefulSets for storage and RabbitMQ, a Service each |
| Service-to-service communication | services → `coordinator`, `rabbitmq` by DNS name; gateway → services | `make smoke`: request, resource and matching smoke tests pass through the gateway |
| Data distribution over 3 shards | `make k8s-storage-failover` §1, `make topology` | records spread over shards 0–2, each copy on both pods of its shard |
| Storage failure + recovery | `make k8s-storage-failover`: deletes a primary pod | replica promoted, writes and reads continue; the StatefulSet recreates the pod, it syncs and is identical again |
| Pod failure and automatic recovery | `make k8s-recovery` | a deleted pod of each service is replaced in ~7 s; a killed JVM is restarted by the kubelet in ~5 s; ~130 calls per service during each, **none failed**; afterwards a request is MATCHED |
| Scaling | `make k8s-scaling` (or `SERVICE=matching-service REPLICAS=3 tests/e2e/k8s-scaling.sh`) | 2 → 4 pods ready in ~7 s, the new pods serve calls at once, back to 2; ~300 calls, **none failed** |

### The demo script

`make demo` (`scripts/demo.sh`) walks through PDF §10 in order, pausing before each
step (Enter to run) and printing every call and its answer:

| Step | What the audience sees |
|---|---|
| 0 | `kubectl get deploy,statefulset,pods,gateway,httproute` |
| 1. Request creation | a SHELTER request is created (201, `PENDING`, server-computed `geoCell`) and read back: `UNMATCHED`, nothing nearby yet |
| 2. Resource registration | 500 L of water and a 50-place shelter are registered close by |
| 3. Matching | a 20 L water request goes through RabbitMQ and is `MATCHED` (500 → 480 L), with the Matching pod's log lines; then `POST /match/{id}` matches the step-1 shelter request (200) and refuses a second time (409) |
| 4. Data distribution | records per shard before/after creating a resource and a request in six cities, with the shard of each key (`crc32 mod 3`) |
| 5. Async processing | 8 simultaneous requests for 20 meals against 100 meals: exactly 5 `MATCHED`, 3 `UNMATCHED`, 0 left; which of the 2 Matching pods handled each event; queue counters |
| 6. Storage failure | the primary pod of the shard holding the step-3 request is deleted; the replica is promoted (~2 s); reads and 6 new writes succeed |
| 7. Node recovery | the StatefulSet recreates the pod (same name and volume); it re-registers as replica with the same record count |
| 8. Kubernetes recovery | a Request Service pod is deleted; replacement ready in ~6 s; no call fails meanwhile |
| 9. Kubernetes scaling | Request Service 2 → 4 pods, each new pod's share of 40 calls, back to 2; no call fails |

`DEMO_PAUSE=0 make demo` runs straight through (it checks every result and exits
non-zero on a problem, so it doubles as a rehearsal); `DEMO_FROM=6` starts at a step.
Runs take a random spot near Bengaluru, so they do not interfere with each other.

In Kubernetes a deleted storage pod is back within seconds (that is the StatefulSet doing
its job). For a longer outage to talk over, the Compose version keeps the node stopped
until it is started again: `make up-dev && make storage-failover`.

By hand:

```bash
kubectl -n emergency get pods -w                              # watch in another terminal
kubectl -n emergency delete pod <a request-service pod>       # replaced automatically
kubectl -n emergency scale deploy/request-service --replicas=4
kubectl -n emergency delete pod storage-1-0                   # storage failover, then recovery
```

## 5. What's left

| Item | Notes |
|---|---|
| Push `feat/kubernetes` and merge | Local commits only for now |
| Autoscaling (HPA) | Out of scope for CP1/Cloud (spec §7.2); scaling is shown with `kubectl scale` |
| Coordinator with more than 1 pod | Its registry is in memory; would need shared or rebuilt state (future work) |
| Helm, Argo CD, CI pipeline, Prometheus/Grafana | SPE course (December), not Cloud Computing |
