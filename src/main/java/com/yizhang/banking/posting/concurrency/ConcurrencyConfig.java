package com.yizhang.banking.posting.concurrency;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConcurrencyConfig {

    @Bean
    public ConcurrencyStrategy concurrencyStrategy(ConcurrencyProperties props) {
        return switch (props.mode()) {
            case NONE        -> new NoneStrategy();
            case PESSIMISTIC -> new PessimisticStrategy();
            case OPTIMISTIC  -> new OptimisticStrategy();
            case STRIPED     -> new StripedStrategy(props.stripedLocks());
            case EXECUTOR    -> new ExecutorStrategy(props.executorPoolSize());
        };
    }
}
