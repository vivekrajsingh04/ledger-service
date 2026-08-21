package com.ledger.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    /**
     * Tags every metric with the active concurrency strategy.
     *
     * <p>This is what makes the optimistic-vs-pessimistic comparison in the README
     * a single query over one Prometheus series rather than two benchmark runs
     * somebody has to remember to label correctly afterwards.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags(
            @Value("${ledger.concurrency:optimistic}") String concurrencyStrategy) {
        return registry -> registry.config()
                .commonTags("service", "ledger", "concurrency", concurrencyStrategy);
    }

    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
