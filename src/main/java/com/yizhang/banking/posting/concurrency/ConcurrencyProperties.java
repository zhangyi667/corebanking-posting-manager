package com.yizhang.banking.posting.concurrency;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "posting.concurrency")
public record ConcurrencyProperties(ConcurrencyMode mode, int stripedLocks, int executorPoolSize) {}
