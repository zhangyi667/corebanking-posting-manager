package com.yizhang.banking.posting.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.ResponseSpec.ErrorHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
public class RestLedgerClient implements LedgerClient {

    private static final Logger log = LoggerFactory.getLogger(RestLedgerClient.class);

    private final RestClient http;
    private final ObjectMapper mapper;
    private final com.yizhang.banking.posting.metrics.PostingMetrics metrics;

    public RestLedgerClient(RestClient ledgerRestClient, ObjectMapper mapper,
                            com.yizhang.banking.posting.metrics.PostingMetrics metrics) {
        this.http = ledgerRestClient;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    @Override
    @Retry(name = "ledger")
    public AccountCheckResponse checkAccounts(List<String> accountIds) {
        long startNs = System.nanoTime();
        boolean ok = false;
        try {
            AccountCheckResponse out = http.post()
                    .uri("/ledger/v1/accounts/check")
                    .body(Map.of("ids", accountIds))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, raise4xx())
                    .onStatus(HttpStatusCode::is5xxServerError, raise5xx())
                    .body(AccountCheckResponse.class);
            ok = true;
            return out;
        } catch (ResourceAccessException e) {
            // I/O failure (connect timeout, read timeout, refused) — retryable.
            throw new LedgerServerException("ledger I/O failure: " + e.getMessage(), e);
        } finally {
            recordTimer("checkAccounts", startNs, ok);
        }
    }

    @Override
    @Retry(name = "ledger")
    public LedgerTransactionResponse applyTransaction(LedgerTransactionRequest request) {
        long startNs = System.nanoTime();
        boolean ok = false;
        try {
            LedgerTransactionResponse out = http.post()
                    .uri("/ledger/v1/transactions")
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, raise4xx())
                    .onStatus(HttpStatusCode::is5xxServerError, raise5xx())
                    .body(LedgerTransactionResponse.class);
            ok = true;
            return out;
        } catch (ResourceAccessException e) {
            throw new LedgerServerException("ledger I/O failure: " + e.getMessage(), e);
        } finally {
            recordTimer("applyTransaction", startNs, ok);
        }
    }

    private void recordTimer(String op, long startNs, boolean ok) {
        if (metrics != null) {
            metrics.recordLedger(op, java.time.Duration.ofNanos(System.nanoTime() - startNs), ok);
        }
    }

    private ErrorHandler raise4xx() {
        return (req, resp) -> {
            String body = readBody(resp.getBody());
            String code = extractCode(body);
            log.warn("ledger 4xx {} code={} body={}", resp.getStatusCode().value(), code, body);
            throw new LedgerClientException(resp.getStatusCode().value(), code, body);
        };
    }

    private ErrorHandler raise5xx() {
        return (req, resp) -> {
            String body = readBody(resp.getBody());
            log.warn("ledger 5xx {} body={}", resp.getStatusCode().value(), body);
            throw new LedgerServerException("ledger " + resp.getStatusCode().value() + ": " + body);
        };
    }

    private static String readBody(java.io.InputStream in) {
        try (in) {
            return new String(in.readAllBytes());
        } catch (IOException e) {
            return "";
        }
    }

    private String extractCode(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            JsonNode code = node.get("code");
            return code == null ? "unknown" : code.asText();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
