package dev.stockflow.gateway.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;

import dev.stockflow.gateway.security.JwtService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
// Spring Boot tests switch tracing off unless observability is re-enabled
@AutoConfigureObservability
@AutoConfigureWireMock(port = 0)
class TracingGatewayTest {

    // W3C trace context: version-traceid(32 hex)-parentid(16 hex)-flags
    private static final String TRACEPARENT = "00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void resetWireMock() {
        com.github.tomakehurst.wiremock.client.WireMock.reset();
    }

    private String bearer() {
        return "Bearer " + jwtService.generateToken("user-1", List.of("ROLE_USER"));
    }

    @Test
    @DisplayName("A request without trace context starts a trace: the downstream gets a W3C traceparent")
    void gatewayStartsATraceAndForwardsTraceparent() {
        stubFor(get(urlEqualTo("/api/v1/categories")).willReturn(aResponse().withStatus(200).withBody("[]")));

        webTestClient.get().uri("/api/v1/categories")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk();

        verify(getRequestedFor(urlEqualTo("/api/v1/categories")).withHeader("traceparent", matching(TRACEPARENT)));
    }

    @Test
    @DisplayName("An incoming traceparent is continued: same trace id downstream, new span id")
    void gatewayContinuesTheCallersTrace() {
        stubFor(get(urlEqualTo("/api/v1/categories")).willReturn(aResponse().withStatus(200).withBody("[]")));
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        webTestClient.get().uri("/api/v1/categories")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .exchange()
                .expectStatus().isOk();

        verify(getRequestedFor(urlEqualTo("/api/v1/categories"))
                .withHeader("traceparent", matching("00-" + traceId + "-(?!00f067aa0ba902b7)[0-9a-f]{16}-0[01]")));
    }
}
