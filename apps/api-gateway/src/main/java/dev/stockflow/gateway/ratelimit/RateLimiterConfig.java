package dev.stockflow.gateway.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimiterConfig {

    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    public RedisRateLimiter redisRateLimiter(RateLimitProperties properties) {
        return new RedisRateLimiter(
                properties.getReplenishRate(),
                properties.getBurstCapacity(),
                properties.getRequestedTokens()
        );
    }
}
