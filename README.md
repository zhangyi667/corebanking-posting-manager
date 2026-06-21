# corebanking-posting-manager

Learning project: a Spring Boot middleware that accepts double-entry posting requests, validates them, calls a downstream ledger service, persists state, and publishes domain events to Kafka. Focus area: **concurrency control under high traffic**.

Five concurrency strategies are switchable via config and benchmarked side-by-side with k6 + Prometheus + Grafana:

| Mode | Coordination | When to study it |
|--|--|--|
| `NONE` | none | baseline — shows races |
| `PESSIMISTIC` | `SELECT ... FOR UPDATE` | predictable, blocking |
| `OPTIMISTIC` | `@Version` + retry | low-contention fast path |
| `STRIPED` | Guava `Striped<Lock>` | in-process, bounded memory |
| `EXECUTOR` | per-stripe single-thread actor | actor-style serialization |

## Stack

- Java 21, Spring Boot 3.4 (Gradle Kotlin DSL)
- Postgres 16 + Spring Data JPA + Flyway
- Kafka 3.7 (KRaft, single broker for dev)
- Spring AI? — **no**, this is the corebanking-posting-manager service
- Micrometer + Prometheus + Grafana
- Resilience4j (ledger retry)
- Testcontainers (Postgres + Kafka in tests)
- WireMock (ledger stub for load tests)
- k6 (load generator)

## Pipeline

```
client
  │  POST /api/v1/postings  (Idempotency-Key header optional)
  ▼
PostingController
  │
  ▼
IdempotencyService  ── replay cached response if key seen ──► return
  │ (else)
  ▼
ConcurrencyStrategy.execute(orderedAccountIds, work)
  │
  ▼
PostingApplyService.applyInTx        @Transactional
  ├─ validate (2 legs, balanced, distinct accounts, currency match)
  ├─ acquire DB lock (mode-dependent)
  ├─ materialize accounts (call ledger.checkAccounts if missing locally)
  ├─ persist Posting + PostingLeg
  ├─ ledger.applyTransaction (Resilience4j retries 5xx)
  ├─ mark APPLIED
  └─ write OutboxEvent (same DB tx)
  ▼
return 201

OutboxPublisher  @Scheduled(200ms)
  ├─ pickUnsent FOR UPDATE SKIP LOCKED
  ├─ KafkaTemplate.send("posting.applied", postingId, envelope)
  └─ mark sent
```

## REST API

### `POST /api/v1/postings`

```http
POST /api/v1/postings
Content-Type: application/json
Idempotency-Key: 4f3b-... (optional)

{
  "transactionRef": "txn-2026-06-21-001",
  "currency": "USD",
  "legs": [
    {"accountId": "acc-001", "type": "DEBIT",  "amount": "100.00"},
    {"accountId": "acc-002", "type": "CREDIT", "amount": "100.00"}
  ],
  "metadata": {"channel": "ATM"}
}
```

Responses:

| Code | When |
|--|--|
| 201 | created (or idempotent replay returns the same 201) |
| 400 | validation: missing field, bad currency code, ≠2 legs, negative amount |
| 404 | account not found in ledger |
| 409 | duplicate `transactionRef` OR same `Idempotency-Key` with different body |
| 422 | business rule: legs unbalanced, frozen account, currency mismatch, ledger insufficient funds |
| 503 | ledger 5xx after retries |

Error envelope:
```json
{
  "timestamp": "2026-06-21T10:00:00Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "code": "business_rule_violation",
  "message": "legs unbalanced: debit=100 credit=99"
}
```

## Concurrency strategies in detail

All strategies receive the account ids **sorted ascending** so any two requests touching overlapping accounts traverse the same coordination resources in the same order — eliminates deadlock by construction.

### `PESSIMISTIC` (`SELECT ... FOR UPDATE`)
Inside the tx, `AccountRepository.lockAllByIdOrdered` issues a row-level write lock per involved account. Other tx wait until commit/rollback. Behaviour: serialization at the DB; throughput bounded by lock-hold time × contention.

### `OPTIMISTIC` (`@Version`)
`Account.@Version` tracks row revision. Tx calls `EntityManager.lock(account, OPTIMISTIC_FORCE_INCREMENT)` → Hibernate schedules a version bump at flush. Concurrent tx that loaded the row earlier collide on commit → `OptimisticLockException`. `OptimisticStrategy` retries up to 5x with jittered exponential backoff (200µs → 3.2ms). Best at low contention; wasted work explodes when contention rises.

### `STRIPED` (`Striped<Lock>` from Guava)
`Striped.lock(N)` allocates `N` `ReentrantLock`s; account id hashed to a stripe. Bounded memory regardless of account-id cardinality. Locks acquired in sorted order, released in reverse — exception-safe. Pure JVM; no DB lock, no network. Loses to multi-instance deployments unless paired with a distributed lock.

### `EXECUTOR` (actor-per-stripe)
`N` single-thread `ExecutorService`s. Request routed by `Math.floorMod(min(accountId).hashCode(), N)`. Calling thread submits the apply work and blocks on `Future.get()` to keep API semantics synchronous. Same min-id key → same actor → serialization. False sharing identical to STRIPED; no lock contention, just queueing.

### `NONE`
No coordination. Used as a baseline to show race conditions surface under load. Do not run with real money.

## Idempotency

Header: `Idempotency-Key`.

- Server hashes the canonicalised JSON body (SHA-256 hex)
- On replay with same key + same hash → returns the original response (same status code)
- On replay with same key + different hash → `409 idempotency_conflict`
- No key → request is executed without caching
- Secondary guard: `posting.transaction_ref UNIQUE` constraint prevents double persistence even if the idempotency layer is bypassed

Race window: two concurrent calls with the same key may both reach the work step. The first INSERT into `idempotency_key` wins; the loser receives a `DataIntegrityViolationException`, re-reads the winner's record, and returns it. The posting `transaction_ref` UNIQUE catches the duplicate posting before any ledger side-effect.

## Outbox pattern

`OutboxEvent` is persisted in the same DB transaction as the `Posting`. A scheduled poller (`OutboxPublisher`, every 200ms) does:

```sql
SELECT * FROM outbox_event WHERE sent_at IS NULL
ORDER BY created_at ASC
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

For each row: send to Kafka topic `posting.applied`, mark `sent_at`. Same tx — Kafka failure rolls back the mark, row stays unsent.

Topic key is `aggregateId` (posting UUID). Partition count 6. Idempotent producer + `acks=all`.

## Metrics

`/actuator/prometheus` exposes:

| Metric | Tags |
|--|--|
| `posting_applied_total` | `mode`, `outcome={APPLIED,REJECTED,ERROR}` |
| `posting_duration_seconds` | `mode` (histogram + p50/p95/p99) |
| `ledger_client_duration_seconds` | `op={checkAccounts,applyTransaction}`, `outcome` |
| `outbox_published_total` | — |
| `outbox_pending` | — (gauge) |
| `http_server_requests_seconds` | default Spring Web tags |

Tag cardinality intentionally low — no account ids, no posting ids.

## Run

```bash
# 1. infra
docker compose up -d   # postgres, kafka, wiremock(ledger), prometheus, grafana

# 2. set keys/overrides (optional)
cp application-local.yml.example application-local.yml
# edit, e.g.: posting.concurrency.mode: OPTIMISTIC

# 3. start the app
./gradlew bootRun     # http://localhost:8081

# 4. smoke
curl -X POST localhost:8081/api/v1/postings \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: test-001' \
  -d '{
    "transactionRef":"txn-001",
    "currency":"USD",
    "legs":[
      {"accountId":"acc-001","type":"DEBIT","amount":"10.00"},
      {"accountId":"acc-002","type":"CREDIT","amount":"10.00"}
    ]
  }'

# 5. observe
open http://localhost:9090       # Prometheus
open http://localhost:3000       # Grafana
open http://localhost:8081/actuator/prometheus
```

## Load test

```bash
# default ramp 100 → 5k TPS, medium contention
k6 run k6/postings.js

# stress lock modes with hot-pair traffic
k6 run -e CONTENTION=high k6/postings.js

# sweep all 5 modes (manual restart between)
./k6/run-all-modes.sh
```

For each mode, switch:
```yaml
# application-local.yml
posting:
  concurrency:
    mode: STRIPED        # or NONE | PESSIMISTIC | OPTIMISTIC | EXECUTOR
```

Restart, run k6 again. Compare `posting_duration_seconds_bucket{mode="..."}` + error rate in Grafana.

## Tests

```bash
./gradlew test           # 28 unit + slice tests
```

Repository integration tests require a Docker daemon (Testcontainers spins up Postgres).

## Project layout

```
src/main/java/com/yizhang/banking/posting/
├── PostingManagerApplication.java
├── api/                 REST controller, DTOs, exception handler
├── client/              Ledger REST client (RestClient + Resilience4j)
├── concurrency/         5 strategies + selector @Configuration
├── config/              shared @ConfigurationProperties (none yet — domain configs live by feature)
├── domain/              JPA entities + enums
├── idempotency/         Idempotency-Key handling + canonical request hash
├── metrics/             custom Micrometer meters
├── outbox/              transactional outbox publisher
├── repo/                Spring Data JPA repositories
└── service/             PostingService interface + apply pipeline
src/main/resources/
├── application.yml
└── db/migration/V1__init.sql   Flyway schema
ops/                     docker-compose support files (prometheus, grafana, wiremock)
k6/                      load test scripts
```

## Limitations (v1)

- Single-instance only for STRIPED/EXECUTOR (in-process). Distributed lock not implemented.
- Idempotency race window briefly allows duplicate work; the posting UNIQUE constraint stops duplicate state.
- Ledger mock returns canned responses — no real funds check; concurrency contention is on the app, not the downstream.
- No multi-leg postings (>2 legs); enforced at validation.
- No FX / multi-currency postings.
- No authentication.

## License

Personal learning project, no license.
