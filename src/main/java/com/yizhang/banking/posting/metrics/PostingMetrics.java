package com.yizhang.banking.posting.metrics;

import com.yizhang.banking.posting.concurrency.ConcurrencyMode;
import com.yizhang.banking.posting.repo.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Central registry of custom posting metrics. Tags are kept low-cardinality:
 * {@code mode}, {@code outcome}, {@code op} — never include account id, posting id, etc.
 */
@Component
public class PostingMetrics {

    public enum Outcome { APPLIED, REJECTED, ERROR }

    private final MeterRegistry registry;
    private final OutboxEventRepository outboxRepo;

    private final Map<ConcurrencyMode, Map<Outcome, Counter>> appliedCounters = new EnumMap<>(ConcurrencyMode.class);
    private final Map<ConcurrencyMode, Timer> postingTimers = new EnumMap<>(ConcurrencyMode.class);

    public PostingMetrics(MeterRegistry registry, OutboxEventRepository outboxRepo) {
        this.registry = registry;
        this.outboxRepo = outboxRepo;
    }

    @PostConstruct
    void register() {
        for (ConcurrencyMode mode : ConcurrencyMode.values()) {
            Map<Outcome, Counter> byOutcome = new EnumMap<>(Outcome.class);
            for (Outcome outcome : Outcome.values()) {
                byOutcome.put(outcome, Counter.builder("posting.applied.total")
                        .description("Postings handled, by concurrency mode and outcome")
                        .tags(Tags.of("mode", mode.name(), "outcome", outcome.name()))
                        .register(registry));
            }
            appliedCounters.put(mode, byOutcome);

            postingTimers.put(mode, Timer.builder("posting.duration")
                    .description("End-to-end apply latency")
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .publishPercentileHistogram()
                    .tags(Tags.of("mode", mode.name()))
                    .register(registry));
        }

        io.micrometer.core.instrument.Gauge.builder("outbox.pending", outboxRepo, OutboxEventRepository::countBySentAtIsNull)
                .description("Unsent outbox rows")
                .register(registry);
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void recordApply(ConcurrencyMode mode, Timer.Sample sample, Outcome outcome) {
        sample.stop(postingTimers.get(mode));
        appliedCounters.get(mode).get(outcome).increment();
    }

    public void recordLedger(String op, Duration elapsed, boolean success) {
        Timer.builder("ledger.client.duration")
                .description("Ledger HTTP client call latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .tags(Tags.of("op", op, "outcome", success ? "success" : "failure"))
                .register(registry)
                .record(elapsed);
    }

    public void recordOutboxPublished(int count) {
        registry.counter("outbox.published.total").increment(count);
    }
}
