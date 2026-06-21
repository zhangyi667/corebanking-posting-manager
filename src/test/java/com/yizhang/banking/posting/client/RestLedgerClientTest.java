package com.yizhang.banking.posting.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestLedgerClientTest {

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RestLedgerClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl("http://ledger.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestLedgerClient(builder.build(), mapper, null);
    }

    @Test
    void applyTransactionReturnsParsedResponse() {
        server.expect(requestTo("http://ledger.test/ledger/v1/transactions"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"appliedAt":"2026-06-21T10:00:00Z","newBalances":{"acc-001":"900.00"}}
                        """, MediaType.APPLICATION_JSON));

        LedgerTransactionResponse out = client.applyTransaction(new LedgerTransactionRequest(
                UUID.randomUUID(), "USD",
                List.of(new LedgerEntry("acc-001", new BigDecimal("-100.00")))));

        assertThat(out.newBalances()).containsEntry("acc-001", new BigDecimal("900.00"));
    }

    @Test
    void serverErrorBecomesLedgerServerException() {
        server.expect(requestTo("http://ledger.test/ledger/v1/transactions"))
                .andRespond(withServerError().body("oops"));

        assertThatThrownBy(() -> client.applyTransaction(new LedgerTransactionRequest(
                UUID.randomUUID(), "USD", List.of())))
                .isInstanceOf(LedgerServerException.class);
    }

    @Test
    void clientErrorBecomesLedgerClientException() {
        server.expect(requestTo("http://ledger.test/ledger/v1/transactions"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                              {"code":"insufficient_funds","message":"acc-001 balance 50 < 100"}
                              """));

        assertThatThrownBy(() -> client.applyTransaction(new LedgerTransactionRequest(
                UUID.randomUUID(), "USD", List.of())))
                .isInstanceOfSatisfying(LedgerClientException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(409);
                    assertThat(ex.getCode()).isEqualTo("insufficient_funds");
                });
    }

    @Test
    void checkAccountsParsesAccountInfo() {
        server.expect(requestTo("http://ledger.test/ledger/v1/accounts/check"))
                .andRespond(withSuccess(
                        """
                        {"accounts":{"acc-001":{"accountId":"acc-001","currency":"USD","status":"ACTIVE"}},"missing":["acc-002"]}
                        """, MediaType.APPLICATION_JSON));

        AccountCheckResponse resp = client.checkAccounts(List.of("acc-001", "acc-002"));
        assertThat(resp.accounts()).containsKey("acc-001");
        assertThat(resp.missing()).containsExactly("acc-002");
    }
}
