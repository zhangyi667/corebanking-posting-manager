import http from "k6/http";
import { check, sleep, fail } from "k6";
import { Counter } from "k6/metrics";
import { randomString } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";

// Override at run time:
//   k6 run -e BASE_URL=http://localhost:8081 -e ACCOUNT_POOL=64 -e CONTENTION=high \
//          -e CORRELATION_STRATEGY=fanout -e OUTBOX_DRAIN_SECONDS=20 k6/postings.js
const BASE_URL             = __ENV.BASE_URL             || "http://localhost:8081";
const ACCOUNT_POOL         = parseInt(__ENV.ACCOUNT_POOL || "64");
const CONTENTION           = __ENV.CONTENTION           || "medium";  // low | medium | high
const CORRELATION_STRATEGY = __ENV.CORRELATION_STRATEGY || "fanout";  // fanout | journey
const OUTBOX_DRAIN_SECONDS = parseInt(__ENV.OUTBOX_DRAIN_SECONDS || "20");

const status201 = new Counter("status_201");
const status4xx = new Counter("status_4xx");
const status5xx = new Counter("status_5xx");

// `low`     — uniform random pair across the pool
// `medium`  — 20% of traffic hits the same hot pair
// `high`    — 80% of traffic hits the same hot pair (worst case for any mode)
const HOT_DEBIT  = "acc-hot-0";
const HOT_CREDIT = "acc-hot-1";

export const options = {
    scenarios: {
        ramp: {
            executor: "ramping-arrival-rate",
            startRate: 100,
            timeUnit: "1s",
            preAllocatedVUs: 200,
            maxVUs: 800,
            stages: [
                { duration: "30s", target: 500 },
                { duration: "60s", target: 1500 },
                { duration: "60s", target: 3000 },
                { duration: "60s", target: 5000 },
                { duration: "30s", target: 5000 },
            ],
        },
    },
    thresholds: {
        http_req_failed: ["rate<0.05"],
        http_req_duration: ["p(95)<200", "p(99)<500"],
        // Custom: at least 95% of requests must have been mirrored to Kafka by teardown.
        // The check is recorded in teardown() via the `outbox_coverage` rate.
        "checks{kind:outbox_coverage}": ["rate>0.95"],
    },
};

// Pull `outbox_published_total` from the app's Prometheus actuator endpoint.
// Counters in Prometheus exposition format render as: `outbox_published_total 12345.0`
function readOutboxPublished() {
    const res = http.get(`${BASE_URL}/actuator/prometheus`, { tags: { kind: "prom_scrape" } });
    if (res.status !== 200) {
        console.warn(`prometheus scrape returned ${res.status}`);
        return null;
    }
    for (const line of res.body.split("\n")) {
        if (line.startsWith("#")) continue;
        // metric name, optional labels, then value
        const m = line.match(/^outbox_published_total(\{[^}]*\})?\s+([0-9.eE+\-]+)/);
        if (m) return parseFloat(m[2]);
    }
    return 0;  // counter never incremented yet
}

export function setup() {
    // Verify the app is reachable before kicking off the ramp.
    const ping = http.get(`${BASE_URL}/actuator/health`);
    if (ping.status !== 200) {
        fail(`app not reachable at ${BASE_URL} — got status ${ping.status}`);
    }
    const baseline = readOutboxPublished();
    console.log(`baseline outbox_published_total=${baseline}`);
    return { baseline: baseline === null ? 0 : baseline, startedAt: Date.now() };
}

function pickAccounts() {
    const r = Math.random();
    const hotThreshold = CONTENTION === "high" ? 0.8 : CONTENTION === "medium" ? 0.2 : 0.0;
    if (r < hotThreshold) {
        return [HOT_DEBIT, HOT_CREDIT];
    }
    const a = Math.floor(Math.random() * ACCOUNT_POOL);
    let b = Math.floor(Math.random() * ACCOUNT_POOL);
    while (b === a) b = (b + 1) % ACCOUNT_POOL;
    return [`acc-${a}`, `acc-${b}`];
}

// `fanout` — each posting gets a unique correlationId (events spread across partitions)
// `journey` — VU reuses one correlationId across all its postings (events ordered per VU)
function pickCorrelationId() {
    return CORRELATION_STRATEGY === "journey"
        ? `journey-vu-${__VU}`
        : `journey-${__VU}-${__ITER}`;
}

export default function () {
    const [debit, credit] = pickAccounts();
    const txnRef = `txn-${__VU}-${__ITER}-${Date.now()}`;
    const body = JSON.stringify({
        transactionRef: txnRef,
        currency: "USD",
        legs: [
            { accountId: debit,  type: "DEBIT",  amount: "10.00" },
            { accountId: credit, type: "CREDIT", amount: "10.00" },
        ],
    });
    const res = http.post(`${BASE_URL}/api/v1/postings`, body, {
        headers: {
            "Content-Type": "application/json",
            "Idempotency-Key": `${__VU}-${__ITER}-${randomString(8)}`,
            "X-Correlation-Id": pickCorrelationId(),
        },
    });
    if (res.status === 201) status201.add(1);
    else if (res.status >= 400 && res.status < 500) status4xx.add(1);
    else if (res.status >= 500) status5xx.add(1);

    check(res, { "non-5xx": (r) => r.status < 500 });
}

export function teardown(data) {
    // Outbox publisher polls every 200ms. Give it time to drain whatever was queued
    // at the end of the ramp before scraping the counter.
    console.log(`waiting ${OUTBOX_DRAIN_SECONDS}s for outbox to drain…`);
    sleep(OUTBOX_DRAIN_SECONDS);

    const final = readOutboxPublished();
    if (final === null) {
        fail("could not read outbox_published_total — prometheus endpoint unavailable");
    }
    const delta = final - data.baseline;

    // Each successful posting writes exactly 3 outbox rows (1 transaction + 2 balance).
    // status_201 is aggregated across all VUs and exposed in the summary report.
    // We can't read its aggregated value mid-script, so we infer expected from
    // http_reqs minus failures via a HEAD scrape of the app's HTTP metric.
    const expected = readSuccessfulPostingsFromProm();
    const expectedEvents = expected * 3;

    console.log(`outbox_published_total: baseline=${data.baseline} final=${final} delta=${delta}`);
    console.log(`expected events (3 × ${expected} successful postings) = ${expectedEvents}`);

    // Custom check tagged so the thresholds block above can gate the build.
    check(null, {
        "kafka events ≥ 95% of expected": () => expectedEvents === 0 || delta >= expectedEvents * 0.95,
    }, { kind: "outbox_coverage" });

    if (expectedEvents > 0 && delta < expectedEvents * 0.95) {
        console.error(`FAIL: only ${delta}/${expectedEvents} events published — likely Kafka backlog or producer error`);
    } else {
        console.log("PASS: outbox drained as expected");
    }
}

// Sums posting_applied_total{outcome="APPLIED"} across all modes from Prometheus.
function readSuccessfulPostingsFromProm() {
    const res = http.get(`${BASE_URL}/actuator/prometheus`, { tags: { kind: "prom_scrape" } });
    if (res.status !== 200) return 0;
    let total = 0;
    for (const line of res.body.split("\n")) {
        if (line.startsWith("#")) continue;
        // posting_applied_total{mode="PESSIMISTIC",outcome="APPLIED",} 1234.0
        const m = line.match(/^posting_applied_total\{[^}]*outcome="APPLIED"[^}]*\}\s+([0-9.eE+\-]+)/);
        if (m) total += parseFloat(m[1]);
    }
    return total;
}
