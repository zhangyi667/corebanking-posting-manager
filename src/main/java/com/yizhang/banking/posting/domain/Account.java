package com.yizhang.banking.posting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "account")
public class Account {

    @Id
    private String id;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {}

    public Account(String id, String currency, AccountStatus status) {
        this.id = id;
        this.currency = currency;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getCurrency() { return currency; }
    public AccountStatus getStatus() { return status; }
    public void setStatus(AccountStatus status) { this.status = status; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
}
