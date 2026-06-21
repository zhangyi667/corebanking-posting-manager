package com.yizhang.banking.posting.client;

/** Ledger returned 4xx — caller fault, NOT retryable. */
public class LedgerClientException extends RuntimeException {
    private final int status;
    private final String code;

    public LedgerClientException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
}
