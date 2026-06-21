package com.yizhang.banking.posting.api;

import com.yizhang.banking.posting.domain.LegType;
import com.yizhang.banking.posting.domain.PostingStatus;
import com.yizhang.banking.posting.service.AccountNotFoundException;
import com.yizhang.banking.posting.service.BusinessRuleException;
import com.yizhang.banking.posting.service.DuplicatePostingException;
import com.yizhang.banking.posting.service.LedgerUnavailableException;
import com.yizhang.banking.posting.service.PostingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {PostingController.class, ApiExceptionHandler.class})
class PostingControllerTest {

    @Autowired MockMvc mvc;
    @MockBean PostingService postingService;

    private static final String VALID_BODY = """
            {
              "transactionRef": "txn-001",
              "currency": "USD",
              "legs": [
                {"accountId": "acc-001", "type": "DEBIT",  "amount": "100.00"},
                {"accountId": "acc-002", "type": "CREDIT", "amount": "100.00"}
              ]
            }
            """;

    private static PostingService.ApplyResult success() {
        UUID id = UUID.randomUUID();
        PostingResponse resp = new PostingResponse(
                id, "txn-001", PostingStatus.APPLIED, Instant.now(),
                List.of(
                        new PostingLegResponse("acc-001", LegType.DEBIT, new BigDecimal("100.00")),
                        new PostingLegResponse("acc-002", LegType.CREDIT, new BigDecimal("100.00"))
                ));
        return new PostingService.ApplyResult(201, resp);
    }

    @Test
    void createsPostingReturns201() throws Exception {
        when(postingService.apply(any(), any())).thenReturn(success());

        mvc.perform(post("/api/v1/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "abc-123")
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.legs.length()").value(2));

        verify(postingService).apply(any(), eq("abc-123"));
    }

    @Test
    void idempotencyKeyOptional() throws Exception {
        when(postingService.apply(any(), any())).thenReturn(success());
        mvc.perform(post("/api/v1/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated());
        verify(postingService).apply(any(), eq(null));
    }

    @Test
    void blankTransactionRef400() throws Exception {
        String body = VALID_BODY.replace("\"txn-001\"", "\"\"");
        mvc.perform(post("/api/v1/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    void invalidCurrency400() throws Exception {
        String body = VALID_BODY.replace("\"USD\"", "\"usd\"");
        mvc.perform(post("/api/v1/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void singleLeg400() throws Exception {
        String body = """
                {
                  "transactionRef": "txn-001",
                  "currency": "USD",
                  "legs": [
                    {"accountId": "acc-001", "type": "DEBIT", "amount": "100.00"}
                  ]
                }
                """;
        mvc.perform(post("/api/v1/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativeAmount400() throws Exception {
        String body = VALID_BODY.replace("\"100.00\"", "\"-1.00\"");
        mvc.perform(post("/api/v1/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void accountNotFoundReturns404() throws Exception {
        when(postingService.apply(any(), any()))
                .thenThrow(new AccountNotFoundException("acc-001"));
        mvc.perform(post("/api/v1/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("account_not_found"));
    }

    @Test
    void duplicateTransactionRefReturns409() throws Exception {
        when(postingService.apply(any(), any()))
                .thenThrow(new DuplicatePostingException("txn-001"));
        mvc.perform(post("/api/v1/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("duplicate_transaction_ref"));
    }

    @Test
    void businessRuleReturns422() throws Exception {
        when(postingService.apply(any(), any()))
                .thenThrow(new BusinessRuleException("insufficient funds"));
        mvc.perform(post("/api/v1/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("business_rule_violation"));
    }

    @Test
    void ledgerDownReturns503() throws Exception {
        when(postingService.apply(any(), any()))
                .thenThrow(new LedgerUnavailableException("timeout", new RuntimeException()));
        mvc.perform(post("/api/v1/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ledger_unavailable"));
    }
}
