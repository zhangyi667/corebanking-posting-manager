package com.yizhang.banking.posting.outbox;

import com.yizhang.banking.posting.domain.OutboxEvent;
import com.yizhang.banking.posting.repo.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {

    private OutboxEventRepository repo;
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        repo = mock(OutboxEventRepository.class);
        publisher = new OutboxPublisher(repo, kafka, 100, null);
    }

    @Test
    void sendsAndMarksEachUnsentEvent() {
        OutboxEvent ev = new OutboxEvent(UUID.randomUUID(), "posting.applied",
                Map.of("transactionRef", "txn-001"));
        when(repo.pickUnsent(any(Pageable.class))).thenReturn(List.of(ev));
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        int sent = publisher.publishBatch();

        assertThat(sent).isEqualTo(1);
        assertThat(ev.getSentAt()).isNotNull();
        assertThat(ev.getAttempts()).isEqualTo(1);
        verify(kafka).send(eq(OutboxProperties.TOPIC), eq(ev.getAggregateId().toString()), any());
    }

    @Test
    void kafkaFailureBubblesUpAndLeavesRowUnsent() {
        OutboxEvent ev = new OutboxEvent(UUID.randomUUID(), "posting.applied", Map.of("k", "v"));
        when(repo.pickUnsent(any(Pageable.class))).thenReturn(List.of(ev));
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(failed);

        assertThatThrownBy(() -> publisher.publishBatch())
                .isInstanceOf(CompletionException.class);

        // attempts was incremented before send — recorded even on failure
        assertThat(ev.getAttempts()).isEqualTo(1);
        assertThat(ev.getSentAt()).isNull();
    }

    @Test
    void emptyBatchReturnsZero() {
        when(repo.pickUnsent(any(Pageable.class))).thenReturn(List.of());
        assertThat(publisher.publishBatch()).isZero();
    }
}
