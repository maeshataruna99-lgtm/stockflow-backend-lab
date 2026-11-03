package dev.stockflow.gateway.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.stockflow.gateway.security.JwtService;
import java.util.List;
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
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@AutoConfigureWireMock(port = 0)
class ErrorContractGatewayTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private GatewayErrorHandler errorHandler;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void resetRateLimiter() {
        TestRateLimiterConfig.EXCEED_QUOTA.set(false);
    }

    private String bearer() {
        return "Bearer " + jwtService.generateToken("user-1", List.of("ROLE_USER"));
    }

    /** Asserts the one error shape every gateway-produced error must have. */
    private JsonNode assertProblem(EntityExchangeResult<byte[]> result, HttpStatus status) throws Exception {
        assertThat(result.getStatus()).isEqualTo(status);
        assertThat(result.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode body = objectMapper.readTree(result.getResponseBody());
        assertThat(body.get("status").asInt()).isEqualTo(status.value());
        assertThat(body.get("title").asText()).isEqualTo(status.getReasonPhrase());
        assertThat(body.get("detail").asText()).isNotBlank();
        assertThat(body.get("requestId").asText()).isEqualTo(result.getResponseHeaders().getFirst("X-Request-Id"));
        // Spring's default error attributes expose these; the contract must not
        assertThat(body.fieldNames()).toIterable()
                .doesNotContain("timestamp", "path", "error", "exception", "trace", "message");
        return body;
    }

    @Test
    @DisplayName("An unknown path returns the problem body, not Spring's default error JSON")
    void unknownPathReturnsProblem() throws Exception {
        EntityExchangeResult<byte[]> result = webTestClient.get().uri("/no-such-route")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange().expectBody().returnResult();

        assertProblem(result, HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("429 from the rate limiter has the problem body")
    void rateLimitedResponseReturnsProblem() throws Exception {
        TestRateLimiterConfig.EXCEED_QUOTA.set(true);

        EntityExchangeResult<byte[]> result = webTestClient.get().uri("/api/v1/products")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange().expectBody().returnResult();

        assertProblem(result, HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("401 from authentication has the problem body")
    void unauthorizedResponseReturnsProblem() throws Exception {
        EntityExchangeResult<byte[]> result = webTestClient.get().uri("/api/v1/products")
                .exchange().expectBody().returnResult();

        assertProblem(result, HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("503 from the circuit breaker fallback has the problem body")
    void fallbackResponseReturnsProblem() throws Exception {
        stubFor(get(urlEqualTo("/api/v1/categories")).willReturn(aResponse().withStatus(500)));

        EntityExchangeResult<byte[]> result = webTestClient.get().uri("/api/v1/categories")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange().expectBody().returnResult();

        assertProblem(result, HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("An unexpected exception becomes a generic 500 and its message is not exposed")
    void unexpectedExceptionDoesNotLeakInternals() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));

        errorHandler.handle(exchange, new IllegalStateException("jdbc:postgresql://secret-host:5432 password=hunter2"))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("requestId").doesNotContain("secret-host", "hunter2", "IllegalStateException");
    }

    @Test
    @DisplayName("A ResponseStatusException keeps its status but not its internal reason")
    void statusExceptionReasonIsNotExposed() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));

        errorHandler.handle(exchange, new ResponseStatusException(HttpStatus.BAD_GATEWAY, "upstream 10.1.2.3:8081 refused"))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain("10.1.2.3", "refused");
    }
}
