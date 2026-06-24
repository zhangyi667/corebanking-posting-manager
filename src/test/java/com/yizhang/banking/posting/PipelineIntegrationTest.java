package com.yizhang.banking.posting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhang.banking.posting.api.PostingResponse;
import com.yizhang.banking.posting.client.AccountCheckResponse;
import com.yizhang.banking.posting.client.AccountInfo;
import com.yizhang.banking.posting.client.LedgerClient;
import com.yizhang.banking.posting.client.LedgerTransactionRequest;
import com.yizhang.banking.posting.client.LedgerTransactionResponse;
import com.yizhang.banking.posting.domain.AccountStatus;
import com.yizhang.banking.posting.domain.OutboxEvent;
import com.yizhang.banking.posting.outbox.OutboxProperties;
import com.yizhang.banking.posting.outbox.OutboxPublisher;
import com.yizhang.banking.posting.repo.OutboxEventRepository;
import com.yizhang.banking.posting.repo.PostingRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EmbeddedKafka(partitions = 3, topics = {OutboxProperties.TRANSACTION_TOPIC, OutboxProperties.BALANCE_TOPIC})
class PipelineIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("posting_manager")
            .withUsername("posting")
            .withPassword("posting");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // EmbeddedKafka publishes its broker list under this property automatically.
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired PostingRepository postingRepo;
    @Autowired OutboxEventRepository outboxRepo;
    @Autowired OutboxPublisher publisher;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired ObjectMapper mapper;

    /**
     * Stub the ledger so the test is hermetic. checkAccounts says everything is ACTIVE/USD;
     * applyTransaction returns canned newBalances.
     */
    @MockBean LedgerClient ledger;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void wireMocksAndConsumer() {
        when(ledger.checkAccounts(anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(0);
            Map<String, AccountInfo> infos = new HashMap<>();
            for (String id : ids) infos.put(id, new AccountInfo(id, "USD", AccountStatus.ACTIVE));
            return new AccountCheckResponse(infos, List.of());
        });
        when(ledger.applyTransaction(any(LedgerTransactionRequest.class)))
                .thenAnswer(inv -> {
                    LedgerTransactionRequest req = inv.getArgument(0);
                    Map<String, BigDecimal> bal = new HashMap<>();
                    req.entries().forEach(e -> bal.put(e.accountId(),
                            new BigDecimal("1000.00").add(e.amount())));
                    return new LedgerTransactionResponse(java.time.Instant.now(), bal);
                });

        Properties props = new Properties();
        props.put("bootstrap.servers", broker.getBrokersAsString());
        props.put("group.id", "integration-test-" + System.nanoTime());
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(OutboxProperties.TRANSACTION_TOPIC, OutboxProperties.BALANCE_TOPIC));
    }

    @AfterEach
    void cleanup() {
        if (consumer != null) consumer.close();
    }

    @Test
    void postingFlowsThroughOutboxToBothTopics() throws Exception {
        // 1. POST a posting
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", "journey-42");
        String body = """
                {
                  "transactionRef": "txn-int-001",
                  "currency": "USD",
                  "legs": [
                    {"accountId": "acc-001", "type": "DEBIT",  "amount": "100.00"},
                    {"accountId": "acc-002", "type": "CREDIT", "amount": "100.00"}
                  ]
                }
                """;
        ResponseEntity<PostingResponse> resp = http.exchange(
                "http://localhost:" + port + "/api/v1/postings",
                HttpMethod.POST, new HttpEntity<>(body, headers), PostingResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().correlationId()).isEqualTo("journey-42");
        assertThat(resp.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("journey-42");

        // 2. Verify DB state — one posting, three outbox rows
        assertThat(postingRepo.findByTransactionRef("txn-int-001")).isPresent();
        List<OutboxEvent> all = outboxRepo.findAll();
        assertThat(all).hasSize(3);
        assertThat(all).extracting(OutboxEvent::getEventType)
                .containsExactlyInAnyOrder(
                        "posting.transaction.applied",
                        "account.balance.changed",
                        "account.balance.changed");
        assertThat(all).allMatch(e -> "journey-42".equals(e.getPartitionKey()));

        // 3. Drain outbox synchronously
        int sent = publisher.publishBatch();
        assertThat(sent).isEqualTo(3);
        assertThat(outboxRepo.findAll()).allMatch(e -> e.getSentAt() != null);

        // 4. Consume from Kafka, separate per topic
        Map<String, List<ConsumerRecord<String, String>>> byTopic = drainConsumer(Duration.ofSeconds(10), 3);

        List<ConsumerRecord<String, String>> txnRecords = byTopic.getOrDefault(OutboxProperties.TRANSACTION_TOPIC, List.of());
        List<ConsumerRecord<String, String>> balRecords = byTopic.getOrDefault(OutboxProperties.BALANCE_TOPIC, List.of());

        assertThat(txnRecords).hasSize(1);
        assertThat(balRecords).hasSize(2);

        // 5. All records must be keyed by correlationId, ensuring same partition for the journey
        assertThat(txnRecords.get(0).key()).isEqualTo("journey-42");
        assertThat(balRecords).allMatch(r -> "journey-42".equals(r.key()));
        assertThat(balRecords).extracting(ConsumerRecord::partition).containsOnly(balRecords.get(0).partition());

        // 6. Check transaction event payload
        JsonNode txnEvt = mapper.readTree(txnRecords.get(0).value());
        assertThat(txnEvt.get("eventType").asText()).isEqualTo("posting.transaction.applied");
        JsonNode txnPayload = txnEvt.get("payload");
        assertThat(txnPayload.get("transactionRef").asText()).isEqualTo("txn-int-001");
        assertThat(txnPayload.get("correlationId").asText()).isEqualTo("journey-42");
        assertThat(txnPayload.get("legs")).hasSize(2);

        // 7. Check balance events — each leg surfaces with delta + newBalance
        Map<String, JsonNode> balByAccount = new HashMap<>();
        for (ConsumerRecord<String, String> rec : balRecords) {
            JsonNode env = mapper.readTree(rec.value());
            JsonNode payload = env.get("payload");
            balByAccount.put(payload.get("accountId").asText(), payload);
        }
        assertThat(balByAccount).containsKeys("acc-001", "acc-002");
        assertThat(new BigDecimal(balByAccount.get("acc-001").get("delta").asText()))
                .isEqualTo(new BigDecimal("-100.00"));
        assertThat(new BigDecimal(balByAccount.get("acc-002").get("delta").asText()))
                .isEqualTo(new BigDecimal("100.00"));
        assertThat(balByAccount.get("acc-001").get("newBalance").asText()).isEqualTo("900.00");
        assertThat(balByAccount.get("acc-002").get("newBalance").asText()).isEqualTo("1100.00");
    }

    private Map<String, List<ConsumerRecord<String, String>>> drainConsumer(Duration timeout, int expected) {
        Map<String, List<ConsumerRecord<String, String>>> byTopic = new HashMap<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        int total = 0;
        while (total < expected && System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> r : records) {
                byTopic.computeIfAbsent(r.topic(), k -> new java.util.ArrayList<>()).add(r);
                total++;
            }
        }
        return byTopic;
    }
}
