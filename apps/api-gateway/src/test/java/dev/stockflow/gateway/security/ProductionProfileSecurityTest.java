package dev.stockflow.gateway.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"eureka.client.enabled=false"})
@AutoConfigureWebTestClient
@ActiveProfiles("prod")
class ProductionProfileSecurityTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("ADR 0003 & 0006: Demo login endpoint is completely absent (404) in prod profile")
    void demoAuthEndpointAbsentInProductionProfile() {
        webTestClient.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"root\",\"password\":\"root_demo_password_123\"}")
                .exchange()
                .expectStatus().isNotFound();
    }
}
