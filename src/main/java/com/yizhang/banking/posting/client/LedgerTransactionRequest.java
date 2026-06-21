package com.yizhang.banking.posting.client;

import java.util.List;
import java.util.UUID;

public record LedgerTransactionRequest(UUID postingId, String currency, List<LedgerEntry> entries) {}
