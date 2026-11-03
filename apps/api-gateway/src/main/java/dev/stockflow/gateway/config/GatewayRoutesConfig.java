package dev.stockflow.gateway.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
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
                        .filters(f -> f.requestRateLimiter(c -> c
                                .setRateLimiter(rateLimiter)
                                .setKeyResolver(keyResolver)
                                .setStatusCode(HttpStatus.TOO_MANY_REQUESTS)))
                        .uri("lb://CATALOG-SERVICE"))
                .route("catalog-categories-route", r -> r
                        .path("/api/v1/categories", "/api/v1/categories/**")
                        .filters(f -> f.requestRateLimiter(c -> c
                                .setRateLimiter(rateLimiter)
                                .setKeyResolver(keyResolver)
                                .setStatusCode(HttpStatus.TOO_MANY_REQUESTS)))
                        .uri("lb://CATALOG-SERVICE"))
                .route("warehouse-route", r -> r
                        .path("/api/v1/warehouses", "/api/v1/warehouses/**")
                        .filters(f -> f.requestRateLimiter(c -> c
                                .setRateLimiter(rateLimiter)
                                .setKeyResolver(keyResolver)
                                .setStatusCode(HttpStatus.TOO_MANY_REQUESTS)))
                        .uri("lb://WAREHOUSE-SERVICE"))
                .build();
    }
}
