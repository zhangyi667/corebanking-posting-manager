package com.yizhang.banking.posting.service;

import com.yizhang.banking.posting.api.PostingRequest;
import com.yizhang.banking.posting.api.PostingResponse;

public interface PostingService {

    /**
     * Apply a posting request. The orchestrator is responsible for:
     * - idempotency-key lookup / store (caller-supplied key, may be null)
     * - account validation
     * - selected concurrency strategy
     * - ledger call
     * - outbox event write (same DB tx as posting row)
     * Returns the response that should be serialized to the client.
     */
    ApplyResult apply(PostingRequest request, String idempotencyKey, String correlationId);

    record ApplyResult(int httpStatus, PostingResponse response) {}
}
