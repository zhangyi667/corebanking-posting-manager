package com.yizhang.banking.posting.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "posting.outbox")
public record OutboxProperties(Duration pollInterval, int batchSize) {

    public static final String TOPIC = "posting.applied";
}
