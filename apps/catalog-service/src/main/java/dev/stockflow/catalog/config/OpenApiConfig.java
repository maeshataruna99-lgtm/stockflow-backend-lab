package dev.stockflow.catalog.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document metadata, served with springdoc 2.10.0 (ADR 0001) at
 * /v3/api-docs with Swagger UI at /swagger-ui/index.html. Endpoint
 * behavior itself is documented through annotations on the controllers;
 * every response documented there is behavior this service actually
 * has (verified by tests and live runs, docs/STATUS.md).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI catalogOpenApi() {
        return new OpenAPI().info(new Info()
                .title("StockFlow catalog-service API")
                .description("""
                        Product master data owned by catalog-service (ADR 0002). \
                        Errors are RFC 7807 application/problem+json responses from a \
                        shared @RestControllerAdvice. List endpoints are paginated: \
                        page is 0-based, size defaults to 20 and is clamped to 1..100. \
                        """)
                .version("v1"));
    }
}
