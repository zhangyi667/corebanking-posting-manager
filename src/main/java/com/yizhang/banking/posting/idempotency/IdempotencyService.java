package com.yizhang.banking.posting.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhang.banking.posting.api.PostingResponse;
import com.yizhang.banking.posting.domain.IdempotencyRecord;
import com.yizhang.banking.posting.repo.IdempotencyRecordRepository;
import com.yizhang.banking.posting.service.IdempotencyConflictException;
import com.yizhang.banking.posting.service.PostingService.ApplyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository repo;
    private final RequestHasher hasher;
    private final ObjectMapper mapper;

    public IdempotencyService(IdempotencyRecordRepository repo, RequestHasher hasher, ObjectMapper mapper) {
        this.repo = repo;
        this.hasher = hasher;
        this.mapper = mapper;
    }

    /**
     * If key is null, run the work directly. Otherwise:
     *  - replay an existing record (same hash) without re-running the work
     *  - reject a key reused with a different request body (409)
     *  - else execute the work and persist the result keyed by Idempotency-Key
     *
     * Concurrent same-key/same-body race: both threads may execute the work; the second
     * INSERT loses on PK and falls back to reading the winner's response. The posting
     * UNIQUE(transaction_ref) constraint is the secondary guard against duplicate posting rows.
     */
    public ApplyResult execute(String idempotencyKey, Object requestBody, Supplier<ApplyResult> work) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return work.get();
        }

        String requestHash = hasher.hash(requestBody);

        Optional<IdempotencyRecord> existing = repo.findById(idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash, idempotencyKey);
        }

        ApplyResult result = work.get();
        try {
            storeRecord(idempotencyKey, requestHash, result);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request wrote it first. Re-read and replay theirs.
            log.warn("idempotency PK collision for {} — replaying winner", idempotencyKey);
            IdempotencyRecord winner = repo.findById(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("PK violation but no row visible", e));
            return replayOrConflict(winner, requestHash, idempotencyKey);
        }
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void storeRecord(String key, String requestHash, ApplyResult result) {
        String json;
        try {
            json = mapper.writeValueAsString(result.response());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
        repo.save(new IdempotencyRecord(
                key, requestHash, result.response().postingId(), json, result.httpStatus()));
    }

    private ApplyResult replayOrConflict(IdempotencyRecord rec, String requestHash, String key) {
        if (!rec.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(key);
        }
        try {
            PostingResponse resp = mapper.readValue(rec.getResponseJson(), PostingResponse.class);
            return new ApplyResult(rec.getStatusCode(), resp);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt cached response for key " + key, e);
        }
    }
}
