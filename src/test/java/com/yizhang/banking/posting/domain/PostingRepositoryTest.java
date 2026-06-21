package com.yizhang.banking.posting.domain;

import com.yizhang.banking.posting.AbstractIntegrationTest;
import com.yizhang.banking.posting.repo.AccountRepository;
import com.yizhang.banking.posting.repo.PostingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PostingRepositoryTest extends AbstractIntegrationTest {

    @Autowired AccountRepository accounts;
    @Autowired PostingRepository postings;

    @Test
    @Transactional
    void persistsPostingWithLegsAndMetadata() {
        accounts.save(new Account("acc-001", "USD", AccountStatus.ACTIVE));
        accounts.save(new Account("acc-002", "USD", AccountStatus.ACTIVE));

        Posting p = new Posting(UUID.randomUUID(), "txn-001", "USD", Map.of("channel", "ATM"));
        p.addLeg("acc-001", LegType.DEBIT,  new BigDecimal("100.00"));
        p.addLeg("acc-002", LegType.CREDIT, new BigDecimal("100.00"));
        postings.save(p);

        Optional<Posting> found = postings.findByTransactionRef("txn-001");
        assertThat(found).isPresent();
        assertThat(found.get().getLegs()).hasSize(2);
        assertThat(found.get().getStatus()).isEqualTo(PostingStatus.PENDING);
        assertThat(found.get().getMetadata()).containsEntry("channel", "ATM");
    }
}
