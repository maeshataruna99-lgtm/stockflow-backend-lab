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
import org.springframework.http.HttpStatus;

@Configuration
public class GatewayRoutesConfig {

    private final RateLimiter<?> rateLimiter;
    private final KeyResolver keyResolver;

    public GatewayRoutesConfig(
            RateLimiter<?> rateLimiter,
            @Qualifier("userOrIpKeyResolver") KeyResolver keyResolver
    ) {
        this.rateLimiter = rateLimiter;
        this.keyResolver = keyResolver;
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

    /** Rate limit first, then a per-route circuit breaker; 5xx and timeouts end in the 503 fallback. */
    private GatewayFilterSpec protect(GatewayFilterSpec f, String breakerName) {
        return f.requestRateLimiter(c -> c
                        .setRateLimiter(rateLimiter)
                        .setKeyResolver(keyResolver)
                        .setStatusCode(HttpStatus.TOO_MANY_REQUESTS))
                .circuitBreaker(c -> c
                        .setName(breakerName)
                        .setFallbackUri("forward:/fallback")
                        .setStatusCodes(Set.of("500", "502", "503", "504")));
    }
}
