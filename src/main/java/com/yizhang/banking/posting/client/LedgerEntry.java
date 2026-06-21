package com.yizhang.banking.posting.client;

import java.math.BigDecimal;

/** Signed entry sent to ledger: negative = debit, positive = credit. */
public record LedgerEntry(String accountId, BigDecimal amount) {}
