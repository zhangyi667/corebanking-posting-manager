package com.yizhang.banking.posting.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record PostingRequest(
        @NotBlank @Size(max = 128) String transactionRef,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be ISO-4217 3-letter") String currency,
        @NotEmpty @Size(min = 2, max = 2, message = "exactly 2 legs (one DEBIT + one CREDIT) required")
        @Valid List<PostingLegRequest> legs,
        Map<String, Object> metadata
) {}
