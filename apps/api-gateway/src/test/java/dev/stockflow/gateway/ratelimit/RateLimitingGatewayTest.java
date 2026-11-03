package dev.stockflow.gateway.ratelimit;

import com.github.tomakehurst.wiremock.client.WireMock;
import dev.stockflow.gateway.config.TestRateLimiterConfig;
import dev.stockflow.gateway.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@AutoConfigureWireMock(port = 0)
class RateLimitingGatewayTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtService jwtService;

    @AfterEach
    void resetRateLimiter() {
        TestRateLimiterConfig.EXCEED_QUOTA.set(false);
    }

    @Test
    @DisplayName("Normal traffic within quota returns 200 with X-RateLimit headers")
    void normalTrafficWithinQuotaSucceedsWithRateLimitHeaders() {
        TestRateLimiterConfig.EXCEED_QUOTA.set(false);
        String token = jwtService.generateToken("user-1", List.of("ROLE_USER"));

        stubFor(get(urlEqualTo("/api/v1/products"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("[{\"id\":1}]")
                        .withStatus(200)));

        webTestClient.get()
                .uri("/api/v1/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Remaining", "19")
                .expectHeader().valueEquals("X-RateLimit-Replenish-Rate", "10")
                .expectHeader().valueEquals("X-RateLimit-Burst-Capacity", "20")
                .expectBody()
                .jsonPath("$[0].id").isEqualTo(1);
    }

    @Test
    @DisplayName("Exceeding quota triggers 429 Too Many Requests and returns X-RateLimit-Remaining: 0")
    void exceedingQuotaReturns429TooManyRequests() {
        TestRateLimiterConfig.EXCEED_QUOTA.set(true);
        String token = jwtService.generateToken("user-1", List.of("ROLE_USER"));

        webTestClient.get()
                .uri("/api/v1/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0");

        // Verify downstream WireMock was never invoked because request was blocked at perimeter
        WireMock.verify(0, WireMock.getRequestedFor(WireMock.urlEqualTo("/api/v1/products")));
    }
}
