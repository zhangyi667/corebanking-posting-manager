package com.yizhang.banking.posting.api;

import com.yizhang.banking.posting.service.AccountNotFoundException;
import com.yizhang.banking.posting.service.BusinessRuleException;
import com.yizhang.banking.posting.service.DuplicatePostingException;
import com.yizhang.banking.posting.service.IdempotencyConflictException;
import com.yizhang.banking.posting.service.LedgerUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, "validation_failed", msg);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadArg(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, "bad_request", e.getMessage());
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleAccountMissing(AccountNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "account_not_found", e.getMessage());
    }

    @ExceptionHandler(DuplicatePostingException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicatePostingException e) {
        return error(HttpStatus.CONFLICT, "duplicate_transaction_ref", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotencyConflict(IdempotencyConflictException e) {
        return error(HttpStatus.CONFLICT, "idempotency_conflict", e.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessRuleException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "business_rule_violation", e.getMessage());
    }

    @ExceptionHandler(LedgerUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleLedger(LedgerUnavailableException e) {
        log.warn("ledger unavailable", e);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "ledger_unavailable", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(Exception e) {
        log.error("unhandled error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "code", code,
                "message", message == null ? "" : message
        ));
    }
}
