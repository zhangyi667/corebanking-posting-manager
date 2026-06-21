-- Accounts: local mirror used for concurrency control and FK target.
-- The authoritative balance lives in the ledger service; this row is materialized
-- on first reference so the posting flow can lock/version it.
CREATE TABLE account (
    id           VARCHAR(64)   PRIMARY KEY,
    currency     CHAR(3)       NOT NULL,
    status       VARCHAR(16)   NOT NULL,   -- ACTIVE | FROZEN | CLOSED
    version      BIGINT        NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE posting (
    id               UUID          PRIMARY KEY,
    transaction_ref  VARCHAR(128)  NOT NULL UNIQUE,
    currency         CHAR(3)       NOT NULL,
    status           VARCHAR(16)   NOT NULL,   -- PENDING | APPLIED | FAILED
    applied_at       TIMESTAMPTZ,
    failure_reason   TEXT,
    metadata         JSONB,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_posting_status ON posting (status) WHERE status = 'PENDING';

CREATE TABLE posting_leg (
    id           BIGSERIAL    PRIMARY KEY,
    posting_id   UUID         NOT NULL REFERENCES posting(id) ON DELETE CASCADE,
    account_id   VARCHAR(64)  NOT NULL REFERENCES account(id),
    leg_type     VARCHAR(8)   NOT NULL,        -- DEBIT | CREDIT
    amount       NUMERIC(20,4) NOT NULL CHECK (amount > 0)
);
CREATE INDEX idx_posting_leg_account ON posting_leg (account_id);

-- Client-supplied Idempotency-Key. Stores prior response so replays are constant-time.
CREATE TABLE idempotency_key (
    key            VARCHAR(128) PRIMARY KEY,
    request_hash   VARCHAR(64)  NOT NULL,
    posting_id     UUID         REFERENCES posting(id),
    response_json  TEXT         NOT NULL,
    status_code    INT          NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_idempotency_created_at ON idempotency_key (created_at);

-- Transactional outbox: written in the same DB tx as the posting,
-- drained by a scheduled publisher to Kafka.
CREATE TABLE outbox_event (
    id           BIGSERIAL    PRIMARY KEY,
    aggregate_id UUID         NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at      TIMESTAMPTZ,
    attempts     INT          NOT NULL DEFAULT 0
);
CREATE INDEX idx_outbox_unsent ON outbox_event (created_at) WHERE sent_at IS NULL;
