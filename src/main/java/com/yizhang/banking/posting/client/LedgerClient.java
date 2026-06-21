package com.yizhang.banking.posting.client;

import java.util.List;

public interface LedgerClient {

    AccountCheckResponse checkAccounts(List<String> accountIds);

    LedgerTransactionResponse applyTransaction(LedgerTransactionRequest request);
}
