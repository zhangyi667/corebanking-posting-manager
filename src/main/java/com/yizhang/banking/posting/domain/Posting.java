package com.yizhang.banking.posting.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "posting")
public class Posting {

    @Id
    private UUID id;

    @Column(name = "transaction_ref", nullable = false, unique = true, length = 128)
    private String transactionRef;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PostingStatus status;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "posting", cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<PostingLeg> legs = new ArrayList<>();

    protected Posting() {}

    public Posting(UUID id, String transactionRef, String currency, Map<String, Object> metadata) {
        this.id = id;
        this.transactionRef = transactionRef;
        this.currency = currency;
        this.metadata = metadata;
        this.status = PostingStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void addLeg(String accountId, LegType type, java.math.BigDecimal amount) {
        PostingLeg leg = new PostingLeg(this, accountId, type, amount);
        legs.add(leg);
    }

    public void markApplied() {
        this.status = PostingStatus.APPLIED;
        this.appliedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = PostingStatus.FAILED;
        this.failureReason = reason;
    }

    public UUID getId() { return id; }
    public String getTransactionRef() { return transactionRef; }
    public String getCurrency() { return currency; }
    public PostingStatus getStatus() { return status; }
    public Instant getAppliedAt() { return appliedAt; }
    public String getFailureReason() { return failureReason; }
    public Map<String, Object> getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }
    public List<PostingLeg> getLegs() { return legs; }
}
