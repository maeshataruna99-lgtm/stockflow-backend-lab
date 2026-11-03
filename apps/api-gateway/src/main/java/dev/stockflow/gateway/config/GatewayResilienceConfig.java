package dev.stockflow.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayResilienceConfig {

    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCircuitBreaker(CircuitBreakerProperties p) {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowSize(p.getSlidingWindowSize())
                        .minimumNumberOfCalls(p.getMinimumNumberOfCalls())
                        .failureRateThreshold(p.getFailureRateThreshold())
                        .waitDurationInOpenState(p.getWaitDurationInOpenState())
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(p.getTimeout()).build())
                .build());
    }
}
