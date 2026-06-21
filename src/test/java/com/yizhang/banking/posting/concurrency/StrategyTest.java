package com.yizhang.banking.posting.concurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategyTest {

    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        if (pool != null) pool.shutdownNow();
    }

    @Test
    void noneStrategyJustExecutes() {
        AtomicInteger n = new AtomicInteger();
        new NoneStrategy().execute(List.of("a"), () -> n.incrementAndGet());
        assertThat(n).hasValue(1);
    }

    @Test
    void optimisticRetriesAndSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        Integer out = new OptimisticStrategy().execute(List.of("a"), () -> {
            if (attempts.incrementAndGet() < 3) throw new OptimisticLockingFailureException("collide");
            return 42;
        });
        assertThat(out).isEqualTo(42);
        assertThat(attempts).hasValue(3);
    }

    @Test
    void optimisticGivesUpAfterMaxAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> new OptimisticStrategy().execute(List.of("a"), () -> {
            attempts.incrementAndGet();
            throw new OptimisticLockingFailureException("always");
        })).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(attempts.get()).isEqualTo(5);
    }

    @Test
    void stripedSerializesPerSameAccount() throws InterruptedException {
        StripedStrategy strategy = new StripedStrategy(16);
        pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();

        for (int i = 0; i < 8; i++) {
            pool.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException ignored) {}
                strategy.execute(List.of("acc-001"), () -> {
                    int now = concurrent.incrementAndGet();
                    maxConcurrent.updateAndGet(m -> Math.max(m, now));
                    try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                    concurrent.decrementAndGet();
                    return null;
                });
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(maxConcurrent.get()).isEqualTo(1);
    }

    @Test
    void stripedReleasesLocksAfterException() {
        StripedStrategy strategy = new StripedStrategy(4);
        AtomicBoolean firstRan = new AtomicBoolean(false);
        assertThatThrownBy(() -> strategy.execute(List.of("a", "b"), () -> {
            throw new RuntimeException("boom");
        })).hasMessage("boom");

        // Locks must be released — a follow-up call must not block.
        strategy.execute(List.of("a", "b"), () -> { firstRan.set(true); return null; });
        assertThat(firstRan).isTrue();
    }

    @Test
    void executorSerializesPerStripe() throws InterruptedException {
        ExecutorStrategy strategy = new ExecutorStrategy(4);
        try {
            pool = Executors.newFixedThreadPool(8);
            ConcurrentLinkedQueue<String> threadNames = new ConcurrentLinkedQueue<>();
            CountDownLatch done = new CountDownLatch(8);
            for (int i = 0; i < 8; i++) {
                pool.submit(() -> {
                    try {
                        strategy.execute(List.of("acc-001"), () -> {
                            threadNames.add(Thread.currentThread().getName());
                            try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                            return null;
                        });
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            // All work for the same key must run on the same actor thread.
            assertThat(new java.util.HashSet<>(threadNames)).hasSize(1);
            assertThat(threadNames.peek()).startsWith("posting-actor-");
        } finally {
            strategy.shutdown();
        }
    }
}
