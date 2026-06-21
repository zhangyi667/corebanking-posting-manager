package com.yizhang.banking.posting.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "posting.ledger")
public record LedgerProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {}
