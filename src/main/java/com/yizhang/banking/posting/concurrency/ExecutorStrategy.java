package com.yizhang.banking.posting.concurrency;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Actor-style: each request hops to a single-thread executor selected by hashing
 * the smallest-id account it touches. Postings whose smallest account hashes to the
 * same stripe execute serially. Concurrency across stripes is bounded by the pool size.
 * Trade-off: requests touching disjoint accounts that happen to share a stripe
 * are serialized unnecessarily (false sharing), but no JVM locks are taken.
 */
public class ExecutorStrategy implements ConcurrencyStrategy {

    private final ExecutorService[] stripes;

    public ExecutorStrategy(int poolSize) {
        this.stripes = new ExecutorService[poolSize];
        for (int i = 0; i < poolSize; i++) {
            int idx = i;
            this.stripes[i] = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "posting-actor-" + idx);
                t.setDaemon(true);
                return t;
            });
        }
    }

    @Override public ConcurrencyMode mode() { return ConcurrencyMode.EXECUTOR; }

    @Override
    public <R> R execute(List<String> orderedAccountIds, Supplier<R> work) {
        // Lowest-id keys the stripe selection — same pair of accounts always routes to same stripe.
        String key = orderedAccountIds.get(0);
        int idx = Math.floorMod(key.hashCode(), stripes.length);
        Future<R> future = stripes[idx].submit(work::get);
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new RuntimeException("interrupted waiting for actor", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        }
    }

    public void shutdown() {
        for (ExecutorService es : stripes) es.shutdown();
    }
}
