package dev.stockflow.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Configuration
public class TestRateLimiterConfig {

    public static final AtomicBoolean EXCEED_QUOTA = new AtomicBoolean(false);

    @Bean
    @Primary
    public RateLimiter<Object> testRateLimiter() {
        return new RateLimiter<>() {
            @Override
            public Mono<Response> isAllowed(String routeId, String id) {
                if (EXCEED_QUOTA.get()) {
                    Map<String, String> headers = Map.of(
                            "X-RateLimit-Replenish-Rate", "10",
                            "X-RateLimit-Burst-Capacity", "20",
                            "X-RateLimit-Remaining", "0"
                    );
                    return Mono.just(new Response(false, headers));
                } else {
                    Map<String, String> headers = Map.of(
                            "X-RateLimit-Replenish-Rate", "10",
                            "X-RateLimit-Burst-Capacity", "20",
                            "X-RateLimit-Remaining", "19"
                    );
                    return Mono.just(new Response(true, headers));
                }
            }

            @Override
            public Class<Object> getConfigClass() {
                return Object.class;
            }

            @Override
            public Object newConfig() {
                return new Object();
            }

            @Override
            public Map<String, Object> getConfig() {
                return Collections.emptyMap();
            }
        };
    }
}
