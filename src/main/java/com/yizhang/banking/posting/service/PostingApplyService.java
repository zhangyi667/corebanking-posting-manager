package com.yizhang.banking.posting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhang.banking.posting.api.PostingLegRequest;
import com.yizhang.banking.posting.api.PostingLegResponse;
import com.yizhang.banking.posting.api.PostingRequest;
import com.yizhang.banking.posting.api.PostingResponse;
import com.yizhang.banking.posting.client.AccountCheckResponse;
import com.yizhang.banking.posting.client.AccountInfo;
import com.yizhang.banking.posting.client.LedgerClient;
import com.yizhang.banking.posting.client.LedgerClientException;
import com.yizhang.banking.posting.client.LedgerEntry;
import com.yizhang.banking.posting.client.LedgerServerException;
import com.yizhang.banking.posting.client.LedgerTransactionRequest;
import com.yizhang.banking.posting.client.LedgerTransactionResponse;
import com.yizhang.banking.posting.concurrency.ConcurrencyMode;
import com.yizhang.banking.posting.domain.Account;
import com.yizhang.banking.posting.domain.AccountStatus;
import com.yizhang.banking.posting.domain.LegType;
import com.yizhang.banking.posting.domain.OutboxEvent;
import com.yizhang.banking.posting.domain.Posting;
import com.yizhang.banking.posting.repo.AccountRepository;
import com.yizhang.banking.posting.repo.OutboxEventRepository;
import com.yizhang.banking.posting.repo.PostingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Single-tx slice of the apply pipeline. Concurrency strategies (none/striped/executor)
 * wrap this. PESSIMISTIC/OPTIMISTIC modes alter behaviour inside the tx via {@code applyInTx}.
 */
@Service
public class PostingApplyService {

    private static final Logger log = LoggerFactory.getLogger(PostingApplyService.class);

    private final AccountRepository accountRepo;
    private final PostingRepository postingRepo;
    private final OutboxEventRepository outboxRepo;
    private final LedgerClient ledger;
    private final EntityManager em;
    private final ObjectMapper mapper;

    public PostingApplyService(AccountRepository accountRepo, PostingRepository postingRepo,
                               OutboxEventRepository outboxRepo, LedgerClient ledger,
                               EntityManager em, ObjectMapper mapper) {
        this.accountRepo = accountRepo;
        this.postingRepo = postingRepo;
        this.outboxRepo = outboxRepo;
        this.ledger = ledger;
        this.em = em;
        this.mapper = mapper;
    }

    @Transactional
    public PostingResponse applyInTx(PostingRequest request, ConcurrencyMode mode, List<String> orderedAccountIds) {
        validateBusinessInvariants(request);
        rejectDuplicateTransactionRef(request.transactionRef());

        switch (mode) {
            case PESSIMISTIC -> accountRepo.lockAllByIdOrdered(orderedAccountIds);
            case OPTIMISTIC -> forceVersionBumpOn(orderedAccountIds);
            case NONE, STRIPED, EXECUTOR -> {}
        }

        Map<String, Account> accounts = loadOrMaterializeAccounts(orderedAccountIds, request.currency());
        Posting posting = persistPosting(request);
        LedgerTransactionResponse ledgerResp = callLedger(posting);
        posting.markApplied();
        writeOutboxEvent(posting);

        log.debug("posting {} applied via mode={} ref={} accounts={}",
                posting.getId(), mode, request.transactionRef(), orderedAccountIds);

        return new PostingResponse(
                posting.getId(),
                posting.getTransactionRef(),
                posting.getStatus(),
                posting.getAppliedAt(),
                posting.getLegs().stream()
                        .map(l -> new PostingLegResponse(l.getAccountId(), l.getLegType(), l.getAmount()))
                        .toList());
    }

    private void validateBusinessInvariants(PostingRequest req) {
        if (req.legs().size() != 2) {
            throw new BusinessRuleException("exactly 2 legs required");
        }
        long debits  = req.legs().stream().filter(l -> l.type() == LegType.DEBIT).count();
        long credits = req.legs().stream().filter(l -> l.type() == LegType.CREDIT).count();
        if (debits != 1 || credits != 1) {
            throw new BusinessRuleException("must contain exactly one DEBIT and one CREDIT leg");
        }
        BigDecimal sumDebit  = sumByType(req, LegType.DEBIT);
        BigDecimal sumCredit = sumByType(req, LegType.CREDIT);
        if (sumDebit.compareTo(sumCredit) != 0) {
            throw new BusinessRuleException("legs unbalanced: debit=" + sumDebit + " credit=" + sumCredit);
        }
        String debitAccount  = req.legs().stream().filter(l -> l.type() == LegType.DEBIT).findFirst().get().accountId();
        String creditAccount = req.legs().stream().filter(l -> l.type() == LegType.CREDIT).findFirst().get().accountId();
        if (debitAccount.equals(creditAccount)) {
            throw new BusinessRuleException("debit and credit accounts must differ");
        }
    }

    private static BigDecimal sumByType(PostingRequest req, LegType type) {
        return req.legs().stream()
                .filter(l -> l.type() == type)
                .map(PostingLegRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void rejectDuplicateTransactionRef(String ref) {
        if (postingRepo.findByTransactionRef(ref).isPresent()) {
            throw new DuplicatePostingException(ref);
        }
    }

    private void forceVersionBumpOn(List<String> accountIds) {
        // Loaded WITHOUT lock; Hibernate schedules a version increment at flush.
        // Concurrent transactions hitting the same row will collide on commit.
        for (String id : accountIds) {
            Account acc = accountRepo.findById(id).orElse(null);
            if (acc != null) {
                em.lock(acc, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
            }
        }
    }

    private Map<String, Account> loadOrMaterializeAccounts(List<String> orderedIds, String currency) {
        Map<String, Account> out = new LinkedHashMap<>();
        List<String> missing = new java.util.ArrayList<>();
        for (String id : orderedIds) {
            accountRepo.findById(id).ifPresentOrElse(a -> out.put(id, a), () -> missing.add(id));
        }
        if (missing.isEmpty()) {
            assertAccountsUsable(out, currency);
            return out;
        }

        AccountCheckResponse check = ledger.checkAccounts(missing);
        for (String id : missing) {
            AccountInfo info = check.accounts() == null ? null : check.accounts().get(id);
            if (info == null) {
                throw new AccountNotFoundException(id);
            }
            try {
                Account fresh = accountRepo.saveAndFlush(new Account(info.accountId(), info.currency(), info.status()));
                out.put(id, fresh);
            } catch (DataIntegrityViolationException race) {
                Account other = accountRepo.findById(id)
                        .orElseThrow(() -> new IllegalStateException("account vanished", race));
                out.put(id, other);
            }
        }
        assertAccountsUsable(out, currency);
        return out;
    }

    private void assertAccountsUsable(Map<String, Account> accounts, String currency) {
        for (Account a : accounts.values()) {
            if (a.getStatus() != AccountStatus.ACTIVE) {
                throw new BusinessRuleException("account " + a.getId() + " is " + a.getStatus());
            }
            if (!a.getCurrency().equals(currency)) {
                throw new BusinessRuleException(
                        "account " + a.getId() + " currency " + a.getCurrency() + " != posting " + currency);
            }
        }
    }

    private Posting persistPosting(PostingRequest req) {
        Posting p = new Posting(UUID.randomUUID(), req.transactionRef(), req.currency(), req.metadata());
        for (PostingLegRequest leg : req.legs()) {
            p.addLeg(leg.accountId(), leg.type(), leg.amount());
        }
        return postingRepo.save(p);
    }

    private LedgerTransactionResponse callLedger(Posting posting) {
        List<LedgerEntry> entries = posting.getLegs().stream()
                .map(l -> new LedgerEntry(l.getAccountId(),
                        l.getLegType() == LegType.CREDIT ? l.getAmount() : l.getAmount().negate()))
                .toList();
        LedgerTransactionRequest req = new LedgerTransactionRequest(posting.getId(), posting.getCurrency(), entries);
        try {
            return ledger.applyTransaction(req);
        } catch (LedgerClientException e) {
            // 4xx from ledger: business rule violation (insufficient funds, frozen, currency mismatch).
            throw new BusinessRuleException("ledger rejected: " + e.getCode() + " — " + e.getMessage());
        } catch (LedgerServerException e) {
            throw new LedgerUnavailableException("ledger 5xx after retries", e);
        }
    }

    private void writeOutboxEvent(Posting posting) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("postingId", posting.getId().toString());
        payload.put("transactionRef", posting.getTransactionRef());
        payload.put("currency", posting.getCurrency());
        payload.put("appliedAt", posting.getAppliedAt() == null ? null : posting.getAppliedAt().toString());
        payload.put("legs", posting.getLegs().stream().map(l -> Map.of(
                "accountId", l.getAccountId(),
                "type", l.getLegType().name(),
                "amount", l.getAmount().toPlainString()
        )).collect(Collectors.toList()));
        outboxRepo.save(new OutboxEvent(posting.getId(), "posting.applied", payload));
    }

    /**
     * Used by idempotency-replay paths if we ever need a fresh, isolated check
     * outside the orchestrator tx. Currently unused but kept as an extension point.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean transactionRefExists(String ref) {
        return postingRepo.findByTransactionRef(ref).isPresent();
    }
}
