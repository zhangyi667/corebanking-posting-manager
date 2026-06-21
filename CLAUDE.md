# corebanking-posting-manager

Learning-focused middleware. Builds while learning — explain concepts alongside code, do not dump full implementation.

## Mission

Sit between a client and the corebanking-ledger service. Accept double-entry posting requests over REST, enforce business invariants, call the ledger synchronously, persist results, publish events to Kafka. Practice high-traffic patterns — primary focus is **concurrency control**.

## Stack

- Java 21, Spring Boot 3.4 (Gradle Kotlin DSL)
- Postgres 16 + Spring Data JPA + Flyway
- Kafka 3.7 (KRaft)
- Micrometer + Prometheus + Grafana
- Resilience4j (retry only — circuit breaker deferred)
- Testcontainers (Postgres + Kafka in tests)
- WireMock (mocks the ledger for load tests since `corebanking-ledger` is still empty)
- k6 (load generator)

## Domain decisions locked

- API: REST `POST /api/v1/postings`
- Posting model: exactly 2 legs, 1 DEBIT + 1 CREDIT, balanced, same currency, distinct accounts
- Idempotency: `Idempotency-Key` header (optional); request hash detects key reuse w/ different body
- Ledger relationship: synchronous REST call; on success emit Kafka event via transactional outbox
- Account validation: every posting checks accounts (local mirror + ledger fallback)
- Concurrency modes: NONE | PESSIMISTIC | OPTIMISTIC | STRIPED | EXECUTOR — switchable via `posting.concurrency.mode`
- Outbox: `outbox_event` row written in same DB tx as posting; scheduled poller drains to Kafka
- Load target: ramp 100 → 5k TPS, p95 < 200ms, p99 < 500ms

## Conventions

- Package-by-feature (api, client, concurrency, idempotency, outbox, service, …) — not by layer
- Constructor injection only. No field `@Autowired`
- Records for DTOs + value objects; entities are classes
- Comments only when WHY non-obvious — identifiers carry WHAT
- Tag cardinality kept low — never include account/posting id in metrics tags
- Lock ordering: sort by id ascending before acquiring DB locks or JVM Stripe locks → deadlock-free by construction
- Idempotent operations (`applyTransaction` on ledger) safe to retry; non-idempotent (account state mutation) not retried

## Pipeline

```
client → PostingController → IdempotencyService.execute(key, body, () ->
   ConcurrencyStrategy.execute(orderedIds, () ->
      PostingApplyService.applyInTx(req, mode, ids)        @Transactional
         ├─ validate invariants
         ├─ acquire lock by mode (PESSIMISTIC/OPTIMISTIC inside tx; others outside)
         ├─ materialize accounts (cached locally; fallback to ledger.checkAccounts)
         ├─ persist Posting + legs
         ├─ ledger.applyTransaction (Resilience4j retry on 5xx)
         ├─ mark APPLIED
         └─ write OutboxEvent
   )
)

OutboxPublisher @Scheduled(200ms)
   → SELECT FOR UPDATE SKIP LOCKED
   → KafkaTemplate.send("posting.applied", postingId, envelope)
   → markSent
```

## Build order (status)

1. ✅ Scaffold (Boot 3.4.1, Gradle, deps, application.yml, /actuator)
2. ✅ Flyway V1 + JPA entities (Account, Posting, PostingLeg, IdempotencyRecord, OutboxEvent)
3. ✅ REST API (`POST /api/v1/postings`) + Bean Validation + global exception handler (400/404/409/422/503)
4. ✅ Ledger REST client (RestClient + Resilience4j retry; 4xx/5xx → different exceptions)
5. ✅ Idempotency layer (canonical SHA-256 of body, replay vs conflict, PK race recovery)
6. ✅ PostingService — 5 concurrency strategies (NONE/PESSIMISTIC/OPTIMISTIC/STRIPED/EXECUTOR) + apply pipeline
7. ✅ Transactional outbox + Kafka publisher (SKIP LOCKED, send-then-mark in single tx)
8. ✅ Metrics (Micrometer Prometheus: posting timer/counter/outbox gauge)
9. ✅ Docker compose + k6 load test (postgres, kafka, wiremock, prometheus, grafana)
10. ✅ README + this design doc

## Deferred / future

- Replace WireMock with the real `corebanking-ledger` service
- Distributed lock (Redis or zk) for multi-instance STRIPED/EXECUTOR
- Circuit breaker on ledger client (Resilience4j `@CircuitBreaker`)
- Dead-letter for outbox rows that exceed retry threshold
- HTTP/2 + connection pooling for ledger client (Apache HttpClient5 or JDK HttpClient)
- Multi-leg postings (>2 legs, balanced)
- FX / multi-currency postings (FX rate service)
- Grafana dashboards committed as JSON
- Authentication / authorization (JWT or mTLS)
- OpenAPI spec + Swagger UI
- Chaos testing (Toxiproxy on ledger client port)

## Key files

```
src/main/java/com/yizhang/banking/posting/
├── PostingManagerApplication.java
├── api/PostingController.java + DTOs + ApiExceptionHandler
├── client/RestLedgerClient.java + LedgerProperties + LedgerClientConfig
├── concurrency/{ConcurrencyMode,ConcurrencyStrategy,*Strategy,ConcurrencyConfig}
├── domain/{Account,Posting,PostingLeg,IdempotencyRecord,OutboxEvent}
├── idempotency/{RequestHasher,IdempotencyService}
├── metrics/PostingMetrics.java
├── outbox/{OutboxProperties,OutboxPublisher,KafkaTopicConfig}
├── repo/...
└── service/{PostingService,DefaultPostingService,PostingApplyService,exceptions}
src/main/resources/db/migration/V1__init.sql
docker-compose.yml
ops/{prometheus.yml, grafana/, ledger-mock/}
k6/{postings.js, run-all-modes.sh}
```

## Run quickstart

```bash
docker compose up -d
./gradlew bootRun                # http://localhost:8081
./gradlew test                   # 28 unit + slice tests
k6 run k6/postings.js            # ramps 100 → 5k TPS
```

Switch concurrency mode per run via `application-local.yml`:
```yaml
posting:
  concurrency:
    mode: STRIPED   # NONE | PESSIMISTIC | OPTIMISTIC | STRIPED | EXECUTOR
```
