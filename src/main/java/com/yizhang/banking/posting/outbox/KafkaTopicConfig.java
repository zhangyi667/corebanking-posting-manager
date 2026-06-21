package com.yizhang.banking.posting.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic postingAppliedTopic() {
        return TopicBuilder.name(OutboxProperties.TOPIC)
                .partitions(6)
                .replicas(1)
                .build();
    }
}
