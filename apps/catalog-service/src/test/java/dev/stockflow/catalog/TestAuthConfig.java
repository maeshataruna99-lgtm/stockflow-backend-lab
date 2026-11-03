package dev.stockflow.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;

/**
 * Every auto-configured MockMvc sends an admin token, so the existing API tests keep exercising the real
 * security filter chain without repeating a header in each call. CatalogSecurityTest builds its own MockMvc
 * and does not get this header.
 */
@TestConfiguration
public class TestAuthConfig {

    @Bean
    MockMvcBuilderCustomizer adminTokenOnEveryRequest() {
        return builder -> builder.defaultRequest(get("/").header(HttpHeaders.AUTHORIZATION,
                "Bearer " + TestTokens.token("test-admin", List.of("ROLE_ADMIN", "ROLE_USER"))));
    }
}
