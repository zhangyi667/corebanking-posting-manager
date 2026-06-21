package com.yizhang.banking.posting.concurrency;

import com.google.common.util.concurrent.Striped;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

/**
 * Bounded set of {@link Lock}s, hashed by account id. Multiple accounts may share
 * a stripe — collisions are tolerated. Locks are acquired in input order, which is
 * pre-sorted by id, so two requests touching overlapping accounts always traverse
 * the same lock sequence — no deadlock.
 */
public class StripedStrategy implements ConcurrencyStrategy {

    private final Striped<Lock> stripes;

    public StripedStrategy(int stripeCount) {
        this.stripes = Striped.lock(stripeCount);
    }

    @Override public ConcurrencyMode mode() { return ConcurrencyMode.STRIPED; }

    @Override
    public <R> R execute(List<String> orderedAccountIds, Supplier<R> work) {
        List<Lock> acquired = new ArrayList<>(orderedAccountIds.size());
        try {
            for (String id : orderedAccountIds) {
                Lock l = stripes.get(id);
                l.lock();
                acquired.add(l);
            }
            return work.get();
        } finally {
            for (int i = acquired.size() - 1; i >= 0; i--) {
                acquired.get(i).unlock();
            }
        }
    }
}
