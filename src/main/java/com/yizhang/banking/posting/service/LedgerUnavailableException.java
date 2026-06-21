package com.yizhang.banking.posting.service;

public class LedgerUnavailableException extends RuntimeException {
    public LedgerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
