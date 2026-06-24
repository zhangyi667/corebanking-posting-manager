# posting-manager Helm chart

Deploys the posting-manager service plus Bitnami Postgres + Kafka subcharts. Intended for local k8s (Docker Desktop, kind, minikube).

## Prereqs

- Docker
- A local k8s cluster (Docker Desktop k8s, kind, or minikube)
- `kubectl`, `helm` 3.13+

## One-shot install

```bash
# 1. Build the image into the cluster's runtime
docker build -t posting-manager:0.0.1 .

# Docker Desktop / minikube w/ docker driver: image is auto-visible.
# kind: load the image into the cluster:
#   kind load docker-image posting-manager:0.0.1

# 2. Pull subchart deps
helm dependency update ./helm

# 3. Install
helm install posting-manager ./helm --values helm/values-local.yaml

# 4. Wait for everything to be Ready
kubectl rollout status deploy/posting-manager
kubectl wait --for=condition=Ready pod -l app.kubernetes.io/instance=posting-manager --timeout=180s
```

## Verify

```bash
# Forward the API to localhost
kubectl port-forward svc/posting-manager 8081:8081 &

# Smoke
curl -X POST http://localhost:8081/api/v1/postings \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: journey-001' \
  -d '{
    "transactionRef":"txn-001",
    "currency":"USD",
    "legs":[
      {"accountId":"acc-001","type":"DEBIT","amount":"10.00"},
      {"accountId":"acc-002","type":"CREDIT","amount":"10.00"}
    ]
  }'

# Tail Kafka
kubectl exec -it posting-manager-kafka-controller-0 -- \
  kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic posting.transaction --from-beginning
```

## Tunables (highlights)

| Path | Default | Notes |
|--|--|--|
| `replicaCount` | 1 | scale stateless app |
| `image.tag` | `0.0.1` | bump when releasing |
| `app.concurrency.mode` | `PESSIMISTIC` | switch strategy w/o code change |
| `app.outbox.pollIntervalMs` | `200` | outbox drain cadence |
| `app.ledger.baseUrl` | `http://ledger-mock:8080` | point at real ledger service |
| `autoscaling.enabled` | `false` | HPA on CPU when true |
| `postgresql.enabled` | `true` | turn off to use external DB |
| `kafka.enabled` | `true` | turn off to use external broker |

Override per-env with `--set` or a values file:
```bash
helm upgrade posting-manager ./helm --set app.concurrency.mode=STRIPED
```

## Uninstall

```bash
helm uninstall posting-manager
# Bitnami subcharts leave PVCs by default; clean up if needed:
kubectl delete pvc -l app.kubernetes.io/instance=posting-manager
```

## Notes

- The `k8s` Spring profile is activated via env. Application config is rendered into a ConfigMap and mounted at `/config/application-k8s.yml`.
- DB password is sourced from the Bitnami Postgres chart's secret (`<release>-postgresql.postgres-password`). Override `database.existingSecret` when wiring an external DB.
- Pods run as non-root (uid 1000) with a read-only root filesystem; `/tmp` is provided as `emptyDir` for Spring Boot.
- The chart auto-creates the two Kafka topics (`posting.transaction`, `posting.balance`) via the Bitnami `provisioning.topics` block. Spring's `NewTopic` beans are a no-op when topics already exist.
- The Bitnami Kafka chart 30.x runs KRaft; no Zookeeper.
