import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";
import { randomString } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";

// Override at run time:
//   k6 run -e BASE_URL=http://localhost:8081 -e ACCOUNT_POOL=64 -e CONTENTION=high k6/postings.js
const BASE_URL    = __ENV.BASE_URL    || "http://localhost:8081";
const ACCOUNT_POOL = parseInt(__ENV.ACCOUNT_POOL || "64");
const CONTENTION  = __ENV.CONTENTION  || "medium"; // low | medium | high

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
    },
};

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
        },
    });
    if (res.status === 201) status201.add(1);
    else if (res.status >= 400 && res.status < 500) status4xx.add(1);
    else if (res.status >= 500) status5xx.add(1);

    check(res, {
        "non-5xx": (r) => r.status < 500,
    });
}
