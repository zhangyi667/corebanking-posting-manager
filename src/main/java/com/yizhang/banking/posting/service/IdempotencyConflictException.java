package com.yizhang.banking.posting.service;

/**
 * Same Idempotency-Key reused with a different request payload.
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String key) {
        super("Idempotency-Key reused with different request body: " + key);
    }
}
