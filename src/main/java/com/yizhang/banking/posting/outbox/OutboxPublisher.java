package com.yizhang.banking.posting.outbox;

import com.yizhang.banking.posting.domain.OutboxEvent;
import com.yizhang.banking.posting.repo.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drains the outbox into Kafka. Pulls a batch under FOR UPDATE SKIP LOCKED so multiple
 * instances can run in parallel without stepping on each other. Sends synchronously and
 * marks rows as sent inside the same DB tx — if Kafka send fails, the tx rolls back and
 * the row stays unsent for the next poll. The Kafka producer is configured with
 * idempotence so duplicate sends caused by retries inside the broker are deduped.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository repo;
    private final KafkaTemplate<String, Object> kafka;
    private final int batchSize;
    private final com.yizhang.banking.posting.metrics.PostingMetrics metrics;

    public OutboxPublisher(OutboxEventRepository repo,
                           KafkaTemplate<String, Object> kafka,
                           @Value("${posting.outbox.batch-size:100}") int batchSize,
                           com.yizhang.banking.posting.metrics.PostingMetrics metrics) {
        this.repo = repo;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${posting.outbox.poll-interval}", initialDelay = 2000)
    public void drain() {
        try {
            int sent = publishBatch();
            if (sent > 0) {
                if (metrics != null) metrics.recordOutboxPublished(sent);
                log.debug("outbox drained {} events", sent);
            }
        } catch (Exception e) {
            // Swallow so the scheduler keeps trying; the row stays unsent for the next tick.
            log.warn("outbox drain failed", e);
        }
    }

    @Transactional
    int publishBatch() {
        List<OutboxEvent> batch = repo.pickUnsent(PageRequest.of(0, batchSize));
        for (OutboxEvent ev : batch) {
            ev.recordAttempt();
            String key = ev.getAggregateId().toString();
            Map<String, Object> envelope = new HashMap<>();
            envelope.put("eventId", ev.getId());
            envelope.put("eventType", ev.getEventType());
            envelope.put("aggregateId", ev.getAggregateId().toString());
            envelope.put("createdAt", ev.getCreatedAt().toString());
            envelope.put("payload", ev.getPayload());
            // .join() turns the async send into sync — exceptions abort the tx, the row stays unsent.
            kafka.send(OutboxProperties.TOPIC, key, envelope).join();
            ev.markSent();
        }
        return batch.size();
    }
}
