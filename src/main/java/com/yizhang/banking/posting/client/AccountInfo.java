package com.yizhang.banking.posting.client;

import com.yizhang.banking.posting.domain.AccountStatus;

public record AccountInfo(String accountId, String currency, AccountStatus status) {}
