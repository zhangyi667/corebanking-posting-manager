package com.yizhang.banking.posting.client;

import java.util.List;
import java.util.Map;

/**
 * Ledger reply: {accountId -> AccountInfo} for those that exist. Missing ones absent from the map.
 * `missing` echoes the original ids that were not found, for convenience.
 */
public record AccountCheckResponse(Map<String, AccountInfo> accounts, List<String> missing) {}
