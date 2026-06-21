package com.yizhang.banking.posting.api;

import com.yizhang.banking.posting.domain.LegType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PostingLegRequest(
        @NotBlank @jakarta.validation.constraints.Size(max = 64) String accountId,
        @NotNull LegType type,
        @NotNull
        @DecimalMin(value = "0.0001", message = "amount must be > 0")
        @Digits(integer = 16, fraction = 4)
        BigDecimal amount
) {}
