package com.yizhang.banking.posting.api;

import com.yizhang.banking.posting.domain.PostingStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PostingResponse(
        UUID postingId,
        String transactionRef,
        String correlationId,
        PostingStatus status,
        Instant appliedAt,
        List<PostingLegResponse> legs
) {}
