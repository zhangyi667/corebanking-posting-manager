package com.yizhang.banking.posting.repo;

import com.yizhang.banking.posting.domain.OutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    // Skip-locked: workers pull disjoint batches without blocking each other.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query(value = "SELECT * FROM outbox_event WHERE sent_at IS NULL " +
            "ORDER BY created_at ASC LIMIT :#{#pageable.pageSize} " +
            "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> pickUnsent(Pageable pageable);

    long countBySentAtIsNull();
}
