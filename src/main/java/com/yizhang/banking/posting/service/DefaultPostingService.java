package com.yizhang.banking.posting.service;

import com.yizhang.banking.posting.api.PostingRequest;
import com.yizhang.banking.posting.api.PostingResponse;
import com.yizhang.banking.posting.concurrency.ConcurrencyStrategy;
import com.yizhang.banking.posting.idempotency.IdempotencyService;
import com.yizhang.banking.posting.metrics.PostingMetrics;
import com.yizhang.banking.posting.metrics.PostingMetrics.Outcome;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
@Primary
public class DefaultPostingService implements PostingService {

    private final PostingApplyService apply;
    private final ConcurrencyStrategy strategy;
    private final IdempotencyService idempotency;
    private final PostingMetrics metrics;

    public DefaultPostingService(PostingApplyService apply, ConcurrencyStrategy strategy,
                                 IdempotencyService idempotency, PostingMetrics metrics) {
        this.apply = apply;
        this.strategy = strategy;
        this.idempotency = idempotency;
        this.metrics = metrics;
    }

    @Override
    public ApplyResult apply(PostingRequest request, String idempotencyKey) {
        return idempotency.execute(idempotencyKey, request, () -> doApply(request));
    }

    private ApplyResult doApply(PostingRequest request) {
        List<String> orderedIds = request.legs().stream()
                .map(l -> l.accountId())
                .sorted(Comparator.naturalOrder())
                .toList();

        Timer.Sample sample = metrics.startTimer();
        try {
            PostingResponse response = strategy.execute(orderedIds,
                    () -> apply.applyInTx(request, strategy.mode(), orderedIds));
            metrics.recordApply(strategy.mode(), sample, Outcome.APPLIED);
            return new ApplyResult(201, response);
        } catch (BusinessRuleException | AccountNotFoundException | DuplicatePostingException e) {
            metrics.recordApply(strategy.mode(), sample, Outcome.REJECTED);
            throw e;
        } catch (RuntimeException e) {
            metrics.recordApply(strategy.mode(), sample, Outcome.ERROR);
            throw e;
        }
    }
}
