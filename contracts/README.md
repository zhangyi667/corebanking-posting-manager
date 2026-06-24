# posting-manager event contracts

Consumer-facing contract for events published by **posting-manager**.

## Topics

| Topic | Cardinality | Key | Value format | Partitions | Replication |
|--|--|--|--|--|--|
| `posting.transaction` | 1 / posting | `correlationId` (UTF-8 string) | JSON envelope | 6 | depends on broker config |
| `posting.balance` | 2 / posting (1 per leg) | `correlationId` (UTF-8 string) | JSON envelope | 6 | depends on broker config |

## Schemas

JSON Schema (draft 2020-12) — files in `schemas/`:

- [`envelope.schema.json`](schemas/envelope.schema.json) — shared wrapper for every message
- [`posting-transaction-applied.schema.json`](schemas/posting-transaction-applied.schema.json) — payload on `posting.transaction`
- [`account-balance-changed.schema.json`](schemas/account-balance-changed.schema.json) — payload on `posting.balance`

Sample messages in `samples/`:

- [`posting-transaction.example.json`](samples/posting-transaction.example.json)
- [`account-balance.example.json`](samples/account-balance.example.json)

There is **no schema registry** in v1. Schema files in this directory are the source of truth.

## Envelope

Every record on every topic looks like:

```json
{
  "eventId": 4201,
  "eventType": "posting.transaction.applied" | "account.balance.changed",
  "aggregateId": "<posting UUID>",
  "partitionKey": "<correlationId>",
  "createdAt": "2026-06-24T10:15:32.481Z",
  "payload": { ... }
}
```

`eventType` discriminates which payload schema applies. Consumers should branch on it.

## Delivery + ordering guarantees

| Property | Value |
|--|--|
| Delivery | **At-least-once.** Outbox + idempotent producer means rare duplicates. Consumers must dedup. |
| Ordering | **Per-partition only.** All events sharing a `partitionKey` (correlationId) land in the same partition and are observed in publish order. Across keys, order is not guaranteed. |
| Producer idempotence | `enable.idempotence=true`, `acks=all` |
| Producer transaction | Not used. Outbox commit + Kafka send happen in the same JVM tx; on failure the send is retried by the next outbox poll. |
| Retention | Inherits broker default. Not pinned by this service. |

## Versioning policy

| Change | Action |
|--|--|
| Add a new optional field to a payload | Same topic, no consumer change needed. Consumers must ignore unknown fields. |
| Remove a field, rename a field, change a field's type, change semantics | New topic suffix `.v2` (e.g., `posting.transaction.v2`). Old topic continues until all consumers cut over. |
| Add a new event type | Either a new topic, or share an existing one if the payload satisfies the existing schema. Document in this file. |

## Consumer group conventions

One consumer group per consuming service:

```
{service}.{topic-or-feature}
```

Examples:
- `analytics-pipeline.posting-balance`
- `notifications.posting-transaction`
- `statement-projector.posting-balance`

This keeps offsets isolated per consumer and makes lag dashboards readable.

## Auth + network (v1)

- Local lab + the dev cluster shipped via Helm: `PLAINTEXT`, no auth, in-cluster service DNS.
- Production: TBD — TLS + SASL/SCRAM expected. This file will be updated with bootstrap servers, truststore, and SASL secret references when prod rolls out.

## Dedup guidance

Either of these works:

- Dedup on `eventId` — monotonic per producer instance. Survives consumer restarts.
- Dedup on `(aggregateId, eventType)` — cross-instance safe; preferred when the consumer pool itself may be replayed.

The same posting will surface as:
- 1 message on `posting.transaction` with `(aggregateId=postingId, eventType=posting.transaction.applied)`
- 2 messages on `posting.balance` with `(aggregateId=postingId, eventType=account.balance.changed)` — distinguish by `payload.accountId`

## Sample consumer config

```properties
bootstrap.servers=posting-manager-kafka:9092
key.deserializer=org.apache.kafka.common.serialization.StringDeserializer
value.deserializer=org.apache.kafka.common.serialization.StringDeserializer

group.id=<your-service>.posting-balance
auto.offset.reset=earliest
enable.auto.commit=false
isolation.level=read_committed
max.poll.records=500
session.timeout.ms=45000
heartbeat.interval.ms=15000
fetch.min.bytes=1
```

## Java consumer (minimal)

```java
Properties props = new Properties();
props.put("bootstrap.servers", "posting-manager-kafka:9092");
props.put("key.deserializer", StringDeserializer.class.getName());
props.put("value.deserializer", StringDeserializer.class.getName());
props.put("group.id", "statement-projector.posting-balance");
props.put("auto.offset.reset", "earliest");
props.put("enable.auto.commit", "false");

ObjectMapper mapper = new ObjectMapper();

try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
    c.subscribe(List.of("posting.balance"));
    while (true) {
        ConsumerRecords<String, String> records = c.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, String> r : records) {
            JsonNode env = mapper.readTree(r.value());
            if (!"account.balance.changed".equals(env.get("eventType").asText())) continue;

            long eventId = env.get("eventId").asLong();
            if (alreadySeen(eventId)) continue;        // dedup

            JsonNode p = env.get("payload");
            String accountId = p.get("accountId").asText();
            BigDecimal delta = new BigDecimal(p.get("delta").asText());
            // ... project into a balance store ...
            markSeen(eventId);
        }
        c.commitSync();
    }
}
```

## Python consumer (minimal)

```python
import json
from confluent_kafka import Consumer

c = Consumer({
    "bootstrap.servers": "posting-manager-kafka:9092",
    "group.id": "analytics-pipeline.posting-transaction",
    "auto.offset.reset": "earliest",
    "enable.auto.commit": False,
})
c.subscribe(["posting.transaction"])

try:
    while True:
        msg = c.poll(1.0)
        if msg is None or msg.error():
            continue
        env = json.loads(msg.value())
        if env["eventType"] != "posting.transaction.applied":
            continue
        if seen(env["eventId"]):
            continue
        handle(env["payload"])
        mark_seen(env["eventId"])
        c.commit(msg)
finally:
    c.close()
```

## Failure modes consumers should plan for

- **Duplicate after producer crash** — outbox replay can resend an already-sent row if `sent_at` was set but the tx commit failed. Dedup.
- **Late arrival** — outbox publisher polls every 200ms; expect that latency.
- **`newBalance: null`** — ledger response was partial. Use the delta to update your own projection; don't crash.
- **Out-of-order across journeys** — `appliedAt` is not a global sort key. Trust per-partition order only.
- **Field added** — non-breaking; ignore unknown keys when deserializing.
- **Topic versioned to `.v2`** — breaking change; switch subscription, drain old topic offset, then switch over.

## Contact

Owner: posting-manager team. Open an issue in the producer repo before depending on undocumented fields.
