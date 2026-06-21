package com.yizhang.banking.posting.concurrency;

public enum ConcurrencyMode {
    /** No coordination — baseline, exhibits races under load. */
    NONE,
    /** SELECT ... FOR UPDATE on involved accounts before mutation. */
    PESSIMISTIC,
    /** @Version on Account; retry on OptimisticLockException. */
    OPTIMISTIC,
    /** Per-account JVM lock via Guava Striped<Lock>; bounded stripe count. */
    STRIPED,
    /** Per-stripe single-thread executor; serializes per stripe in actor style. */
    EXECUTOR
}
