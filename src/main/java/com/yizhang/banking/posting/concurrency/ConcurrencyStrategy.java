package com.yizhang.banking.posting.concurrency;

import java.util.List;
import java.util.function.Supplier;

/**
 * Wraps the transactional posting work with the chosen concurrency primitive.
 * The strategy is responsible only for serializing access. The supplied work is
 * what actually persists/updates state — it always runs in its own transaction.
 */
public interface ConcurrencyStrategy {

    ConcurrencyMode mode();

    /**
     * @param orderedAccountIds account ids touched by the request, pre-sorted ascending
     *                          to guarantee a global lock order across requests.
     */
    <R> R execute(List<String> orderedAccountIds, Supplier<R> work);
}
