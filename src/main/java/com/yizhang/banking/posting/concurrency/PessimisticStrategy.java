package com.yizhang.banking.posting.concurrency;

import java.util.List;
import java.util.function.Supplier;

/**
 * No JVM-side coordination — the DB does the work. The transactional posting work
 * is responsible for issuing SELECT ... FOR UPDATE in the same tx before mutating.
 */
public class PessimisticStrategy implements ConcurrencyStrategy {

    @Override public ConcurrencyMode mode() { return ConcurrencyMode.PESSIMISTIC; }

    @Override
    public <R> R execute(List<String> orderedAccountIds, Supplier<R> work) {
        return work.get();
    }
}
