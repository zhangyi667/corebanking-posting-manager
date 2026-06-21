package com.yizhang.banking.posting.repo;

import com.yizhang.banking.posting.domain.Posting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PostingRepository extends JpaRepository<Posting, UUID> {

    Optional<Posting> findByTransactionRef(String transactionRef);
}
