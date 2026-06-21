package com.yizhang.banking.posting.concurrency;

import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Retries the supplied work when Hibernate's version check fails. Caller's tx
 * has rolled back by the time we see the exception, so each retry runs a fresh tx.
 */
public class OptimisticStrategy implements ConcurrencyStrategy {

    private static final Logger log = LoggerFactory.getLogger(OptimisticStrategy.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final int BASE_BACKOFF_MICROS = 200;

    @Override public ConcurrencyMode mode() { return ConcurrencyMode.OPTIMISTIC; }

    @Override
    public <R> R execute(List<String> orderedAccountIds, Supplier<R> work) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return work.get();
            } catch (OptimisticLockException | OptimisticLockingFailureException e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("optimistic retry exhausted after {} attempts", attempt);
                    throw e;
                }
                // Jittered exponential backoff in microseconds — short waits suit fast txs.
                long sleepMicros = (long) BASE_BACKOFF_MICROS
                        * (1L << (attempt - 1))
                        + ThreadLocalRandom.current().nextInt(BASE_BACKOFF_MICROS);
                try {
                    Thread.sleep(sleepMicros / 1000, (int) (sleepMicros % 1000) * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted during optimistic backoff", ie);
                }
            }
        }
        throw new IllegalStateException("unreachable");
    }
}
