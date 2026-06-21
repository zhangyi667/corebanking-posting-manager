package com.yizhang.banking.posting.service;

public class DuplicatePostingException extends RuntimeException {
    public DuplicatePostingException(String transactionRef) {
        super("transactionRef already used: " + transactionRef);
    }
}
