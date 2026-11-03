package dev.stockflow.gateway.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;

import dev.stockflow.gateway.security.JwtService;
import java.util.List;
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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "stockflow.gateway.circuit-breaker.sliding-window-size=2",
                "stockflow.gateway.circuit-breaker.minimum-number-of-calls=2",
                "stockflow.gateway.circuit-breaker.wait-duration-in-open-state=30s",
                "stockflow.gateway.circuit-breaker.timeout=1s"
        })
@AutoConfigureWebTestClient
@AutoConfigureWireMock(port = 0)
class CircuitBreakerGatewayTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtService jwtService;

    @Test
    @DisplayName("Repeated downstream 5xx opens the circuit: 503 fallback and no further downstream calls")
    void openCircuitReturnsFallbackWithoutCallingDownstream() {
        String token = jwtService.generateToken("user-1", List.of("ROLE_USER"));
        stubFor(get(urlEqualTo("/api/v1/products")).willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 3; i++) {
            webTestClient.get().uri("/api/v1/products")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectBody().jsonPath("$.title").isEqualTo("Service unavailable");
        }

        // the third call was short-circuited, so the downstream saw only the two that opened the circuit
        verify(exactly(2), getRequestedFor(urlEqualTo("/api/v1/products")));
    }

    @Test
    @DisplayName("A downstream slower than the timeout gets the 503 fallback")
    void slowDownstreamTimesOutToFallback() {
        String token = jwtService.generateToken("user-1", List.of("ROLE_USER"));
        stubFor(get(urlEqualTo("/api/v1/categories")).willReturn(aResponse()
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody("[]")
                .withFixedDelay(3000)));

        webTestClient.get().uri("/api/v1/categories")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("A failed write is never reported as success and is not retried")
    void failedWriteReturns503AndIsSentOnce() {
        String token = jwtService.generateToken("admin-1", List.of("ROLE_ADMIN", "ROLE_USER"));
        stubFor(post(urlEqualTo("/api/v1/warehouses")).willReturn(aResponse().withStatus(500)));

        webTestClient.post().uri("/api/v1/warehouses")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"code\":\"WH-X\",\"name\":\"X\"}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody().jsonPath("$.title").isEqualTo("Service unavailable");

        verify(exactly(1), postRequestedFor(urlEqualTo("/api/v1/warehouses")));
    }
}
