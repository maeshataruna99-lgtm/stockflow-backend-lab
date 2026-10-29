package dev.stockflow.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator gatewayRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("catalog-products-route", r -> r
                        .path("/api/v1/products", "/api/v1/products/**")
                        .uri("lb://CATALOG-SERVICE"))
                .route("catalog-categories-route", r -> r
                        .path("/api/v1/categories", "/api/v1/categories/**")
                        .uri("lb://CATALOG-SERVICE"))
                .route("warehouse-route", r -> r
                        .path("/api/v1/warehouses", "/api/v1/warehouses/**")
                        .uri("lb://WAREHOUSE-SERVICE"))
                .build();
    }
}
