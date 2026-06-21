package com.yizhang.banking.posting.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record LedgerTransactionResponse(Instant appliedAt, Map<String, BigDecimal> newBalances) {}
