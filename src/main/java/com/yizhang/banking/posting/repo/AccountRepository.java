package com.yizhang.banking.posting.repo;

import com.yizhang.banking.posting.domain.Account;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, String> {

    // PESSIMISTIC_WRITE = SELECT ... FOR UPDATE in PostgreSQL.
    // Acquires row-level lock for the duration of the surrounding transaction.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id ASC")
    List<Account> lockAllByIdOrdered(@Param("ids") List<String> ids);

    Optional<Account> findById(String id);
}
