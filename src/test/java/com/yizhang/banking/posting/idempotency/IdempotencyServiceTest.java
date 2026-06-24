package com.yizhang.banking.posting.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yizhang.banking.posting.api.PostingRequest;
import com.yizhang.banking.posting.api.PostingLegRequest;
import com.yizhang.banking.posting.api.PostingResponse;
import com.yizhang.banking.posting.domain.IdempotencyRecord;
import com.yizhang.banking.posting.domain.LegType;
import com.yizhang.banking.posting.domain.PostingStatus;
import com.yizhang.banking.posting.repo.IdempotencyRecordRepository;
import com.yizhang.banking.posting.service.IdempotencyConflictException;
import com.yizhang.banking.posting.service.PostingService.ApplyResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyServiceTest {

    private IdempotencyRecordRepository repo;
    private IdempotencyService svc;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        repo = mock(IdempotencyRecordRepository.class);
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        svc = new IdempotencyService(repo, new RequestHasher(mapper), mapper);
    }

    private static PostingRequest sampleReq(String ref) {
        return new PostingRequest(ref, "USD",
                List.of(
                        new PostingLegRequest("acc-001", LegType.DEBIT,  new BigDecimal("100.00")),
                        new PostingLegRequest("acc-002", LegType.CREDIT, new BigDecimal("100.00"))
                ),
                null);
    }

    private static ApplyResult sampleResult() {
        return new ApplyResult(201, new PostingResponse(
                UUID.randomUUID(), "txn-001", "corr-001", PostingStatus.APPLIED,
                Instant.parse("2026-06-21T10:00:00Z"),
                List.of()));
    }

    @Test
    void nullKeyExecutesWork() {
        AtomicInteger calls = new AtomicInteger();
        ApplyResult out = svc.execute(null, sampleReq("txn-001"), () -> {
            calls.incrementAndGet();
            return sampleResult();
        });
        assertThat(calls).hasValue(1);
        assertThat(out.httpStatus()).isEqualTo(201);
    }

    @Test
    void firstCallPersistsResult() {
        when(repo.findById("key-1")).thenReturn(Optional.empty());

        AtomicInteger calls = new AtomicInteger();
        ApplyResult result = sampleResult();
        svc.execute("key-1", sampleReq("txn-001"), () -> {
            calls.incrementAndGet();
            return result;
        });

        assertThat(calls).hasValue(1);
        ArgumentCaptor<IdempotencyRecord> captor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getKey()).isEqualTo("key-1");
        assertThat(captor.getValue().getStatusCode()).isEqualTo(201);
    }

    @Test
    void replayReturnsCachedResultWithoutExecutingWork() throws Exception {
        ApplyResult cached = sampleResult();
        String json = mapper.writeValueAsString(cached.response());
        String hash = new RequestHasher(mapper).hash(sampleReq("txn-001"));
        IdempotencyRecord rec = new IdempotencyRecord("key-1", hash, cached.response().postingId(), json, 201);
        when(repo.findById("key-1")).thenReturn(Optional.of(rec));

        AtomicInteger calls = new AtomicInteger();
        ApplyResult out = svc.execute("key-1", sampleReq("txn-001"), () -> {
            calls.incrementAndGet();
            return sampleResult();
        });

        assertThat(calls).hasValue(0);
        assertThat(out.httpStatus()).isEqualTo(201);
        assertThat(out.response().postingId()).isEqualTo(cached.response().postingId());
    }

    @Test
    void differentBodySameKeyThrowsConflict() {
        IdempotencyRecord rec = new IdempotencyRecord(
                "key-1", "OTHER-HASH", UUID.randomUUID(), "{}", 201);
        when(repo.findById("key-1")).thenReturn(Optional.of(rec));

        assertThatThrownBy(() -> svc.execute("key-1", sampleReq("txn-001"), () -> sampleResult()))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void pkCollisionRecoversByReplayingWinner() throws Exception {
        when(repo.findById("key-1")).thenReturn(Optional.empty());
        when(repo.save(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataIntegrityViolationException("dup"));

        // Winner record visible on re-read
        ApplyResult winnerResult = sampleResult();
        String json = mapper.writeValueAsString(winnerResult.response());
        String hash = new RequestHasher(mapper).hash(sampleReq("txn-001"));
        when(repo.findById("key-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new IdempotencyRecord(
                        "key-1", hash, winnerResult.response().postingId(), json, 201)));

        ApplyResult out = svc.execute("key-1", sampleReq("txn-001"), () -> sampleResult());
        assertThat(out.response().postingId()).isEqualTo(winnerResult.response().postingId());
    }
}
