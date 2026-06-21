package com.yizhang.banking.posting.client;

/** Ledger returned 5xx — transient, retryable. */
public class LedgerServerException extends RuntimeException {
    public LedgerServerException(String message) { super(message); }
    public LedgerServerException(String message, Throwable cause) { super(message, cause); }
}
