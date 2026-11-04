package dev.stockflow.gateway.config;

import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

@Configuration
public class GatewayRoutesConfig {

    private final RateLimiter<?> rateLimiter;
    private final KeyResolver keyResolver;
    private final RetryProperties retry;

    public GatewayRoutesConfig(
            RateLimiter<?> rateLimiter,
            @Qualifier("userOrIpKeyResolver") KeyResolver keyResolver,
            RetryProperties retry
    ) {
        this.rateLimiter = rateLimiter;
        this.keyResolver = keyResolver;
        this.retry = retry;
    }

    @Bean
    public RouteLocator gatewayRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("catalog-products-route", r -> r
                        .path("/api/v1/products", "/api/v1/products/**")
                        .filters(f -> protect(f, "catalog-products"))
                        .uri("lb://CATALOG-SERVICE"))
                .route("catalog-categories-route", r -> r
                        .path("/api/v1/categories", "/api/v1/categories/**")
                        .filters(f -> protect(f, "catalog-categories"))
                        .uri("lb://CATALOG-SERVICE"))
                .route("warehouse-route", r -> r
                        .path("/api/v1/warehouses", "/api/v1/warehouses/**")
                        .filters(f -> protect(f, "warehouse"))
                        .uri("lb://WAREHOUSE-SERVICE"))
                .build();
    }

    /**
     * Rate limit, then a per-route circuit breaker (5xx and timeouts end in the 503 fallback), then retry
     * inside the breaker so the breaker counts the final outcome. Only GET is retried: it is safe to repeat.
     */
    private GatewayFilterSpec protect(GatewayFilterSpec f, String breakerName) {
        return f.requestRateLimiter(c -> c
                        .setRateLimiter(rateLimiter)
                        .setKeyResolver(keyResolver)
                        .setStatusCode(HttpStatus.TOO_MANY_REQUESTS))
                .circuitBreaker(c -> c
                        .setName(breakerName)
                        .setFallbackUri("forward:/fallback")
                        .setStatusCodes(Set.of("500", "502", "503", "504")))
                .removeResponseHeader("Server")
                .addResponseHeader("X-Content-Type-Options", "nosniff")
                .retry(c -> c
                        .setRetries(retry.getRetries())
                        .setMethods(HttpMethod.GET)
                        .setSeries()
                        .setStatuses(HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT)
                        .setBackoff(retry.getFirstBackoff(), retry.getMaxBackoff(), 2, false)
                        .setJitter(retry.getJitter()));
    }
}
