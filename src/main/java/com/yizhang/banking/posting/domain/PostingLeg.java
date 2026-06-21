package com.yizhang.banking.posting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "posting_leg")
public class PostingLeg {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "posting_id", nullable = false)
    private Posting posting;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "leg_type", nullable = false, length = 8)
    private LegType legType;

    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal amount;

    protected PostingLeg() {}

    PostingLeg(Posting posting, String accountId, LegType legType, BigDecimal amount) {
        this.posting = posting;
        this.accountId = accountId;
        this.legType = legType;
        this.amount = amount;
    }

    public Long getId() { return id; }
    public Posting getPosting() { return posting; }
    public String getAccountId() { return accountId; }
    public LegType getLegType() { return legType; }
    public BigDecimal getAmount() { return amount; }
}
