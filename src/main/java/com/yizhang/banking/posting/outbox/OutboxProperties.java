package com.yizhang.banking.posting.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "posting.outbox")
public record OutboxProperties(Duration pollInterval, int batchSize) {

    public static final String TRANSACTION_TOPIC = "posting.transaction";
    public static final String BALANCE_TOPIC     = "posting.balance";

    /** Hardcoded routing from event_type → Kafka topic. */
    public static String topicFor(String eventType) {
        return switch (eventType) {
            case "posting.transaction.applied" -> TRANSACTION_TOPIC;
            case "account.balance.changed"     -> BALANCE_TOPIC;
            default -> throw new IllegalArgumentException("no topic mapped for event_type=" + eventType);
        };
    }
}
