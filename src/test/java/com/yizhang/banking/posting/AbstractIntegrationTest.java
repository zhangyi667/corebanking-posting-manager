package com.yizhang.banking.posting;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the Spring context against a real Postgres in Testcontainers.
 * Kafka autoconfig is excluded — outbox tests that need Kafka spin up their own container.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
})
@Testcontainers
@ExtendWith(AbstractIntegrationTest.PostgresExtension.class)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("posting_manager")
            .withUsername("posting")
            .withPassword("posting")
            .withReuse(true);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    static class PostgresExtension implements org.junit.jupiter.api.extension.BeforeAllCallback {
        @Override public void beforeAll(org.junit.jupiter.api.extension.ExtensionContext ctx) {
            if (!POSTGRES.isRunning()) POSTGRES.start();
        }
    }
}
