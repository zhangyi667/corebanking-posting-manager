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
    void transactionEventRoutesToTransactionTopicKeyedByPartitionKey() {
        UUID postingId = UUID.randomUUID();
        OutboxEvent ev = new OutboxEvent(postingId, "posting.transaction.applied",
                "corr-1", Map.of("transactionRef", "txn-001"));
        when(repo.pickUnsent(any(Pageable.class))).thenReturn(List.of(ev));
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        int sent = publisher.publishBatch();

        assertThat(sent).isEqualTo(1);
        assertThat(ev.getSentAt()).isNotNull();
        assertThat(ev.getAttempts()).isEqualTo(1);
        verify(kafka).send(eq(OutboxProperties.TRANSACTION_TOPIC), eq("corr-1"), any());
    }

    @Test
    void balanceEventRoutesToBalanceTopic() {
        UUID postingId = UUID.randomUUID();
        OutboxEvent ev = new OutboxEvent(postingId, "account.balance.changed",
                "corr-7", Map.of("accountId", "acc-001"));
        when(repo.pickUnsent(any(Pageable.class))).thenReturn(List.of(ev));
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishBatch();

        verify(kafka).send(eq(OutboxProperties.BALANCE_TOPIC), eq("corr-7"), any());
    }

    @Test
    void kafkaFailureBubblesUpAndLeavesRowUnsent() {
        OutboxEvent ev = new OutboxEvent(UUID.randomUUID(), "posting.transaction.applied",
                "corr-x", Map.of("k", "v"));
        when(repo.pickUnsent(any(Pageable.class))).thenReturn(List.of(ev));
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(failed);

        assertThatThrownBy(() -> publisher.publishBatch())
                .isInstanceOf(CompletionException.class);

        assertThat(ev.getAttempts()).isEqualTo(1);
        assertThat(ev.getSentAt()).isNull();
    }

    @Test
    void emptyBatchReturnsZero() {
        when(repo.pickUnsent(any(Pageable.class))).thenReturn(List.of());
        assertThat(publisher.publishBatch()).isZero();
    }

    @Test
    void unknownEventTypeRejected() {
        OutboxEvent ev = new OutboxEvent(UUID.randomUUID(), "weird.event",
                "corr-1", Map.of());
        when(repo.pickUnsent(any(Pageable.class))).thenReturn(List.of(ev));

        assertThatThrownBy(() -> publisher.publishBatch())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weird.event");
    }
}
