package com.yizhang.banking.posting.api;

import com.yizhang.banking.posting.domain.LegType;

import java.math.BigDecimal;

public record PostingLegResponse(String accountId, LegType type, BigDecimal amount) {}
