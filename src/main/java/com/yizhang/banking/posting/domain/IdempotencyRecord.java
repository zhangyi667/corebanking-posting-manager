package com.yizhang.banking.posting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_key")
public class IdempotencyRecord {

    @Id
    @Column(length = 128)
    private String key;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "posting_id")
    private UUID postingId;

    @Column(name = "response_json", nullable = false, columnDefinition = "TEXT")
    private String responseJson;

    @Column(name = "status_code", nullable = false)
    private int statusCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {}

    public IdempotencyRecord(String key, String requestHash, UUID postingId, String responseJson, int statusCode) {
        this.key = key;
        this.requestHash = requestHash;
        this.postingId = postingId;
        this.responseJson = responseJson;
        this.statusCode = statusCode;
        this.createdAt = Instant.now();
    }

    public String getKey() { return key; }
    public String getRequestHash() { return requestHash; }
    public UUID getPostingId() { return postingId; }
    public String getResponseJson() { return responseJson; }
    public int getStatusCode() { return statusCode; }
    public Instant getCreatedAt() { return createdAt; }
}
