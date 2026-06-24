-- Posting carries a client-supplied (or server-generated) correlation id linking
-- multiple postings into a single business journey. Used as the Kafka partition
-- key for downstream events so all events from one journey land in one partition.
ALTER TABLE posting
    ADD COLUMN correlation_id VARCHAR(128);

UPDATE posting SET correlation_id = id::text WHERE correlation_id IS NULL;

ALTER TABLE posting
    ALTER COLUMN correlation_id SET NOT NULL;

CREATE INDEX idx_posting_correlation ON posting (correlation_id);

-- Outbox rows carry the partition key explicitly so the publisher does not need
-- to derive it from the payload. aggregate_id stays as-is (posting UUID) for
-- traceability; partition_key is what gets used as the Kafka message key.
ALTER TABLE outbox_event
    ADD COLUMN partition_key VARCHAR(128);

UPDATE outbox_event SET partition_key = aggregate_id::text WHERE partition_key IS NULL;

ALTER TABLE outbox_event
    ALTER COLUMN partition_key SET NOT NULL;
