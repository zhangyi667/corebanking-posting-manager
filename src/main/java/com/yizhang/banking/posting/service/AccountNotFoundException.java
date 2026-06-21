package com.yizhang.banking.posting.service;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String accountId) {
        super("account not found: " + accountId);
    }
}
