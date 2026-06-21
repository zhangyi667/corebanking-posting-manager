package com.yizhang.banking.posting.concurrency;

import java.util.List;
import java.util.function.Supplier;

public class NoneStrategy implements ConcurrencyStrategy {

    @Override public ConcurrencyMode mode() { return ConcurrencyMode.NONE; }

    @Override
    public <R> R execute(List<String> orderedAccountIds, Supplier<R> work) {
        return work.get();
    }
}
