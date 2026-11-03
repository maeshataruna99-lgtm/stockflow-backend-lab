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

import com.github.tomakehurst.wiremock.stubbing.Scenario;
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

// the breaker window is huge so it never opens here; src/test/resources/application.yml shadows the main one, so the timeout is set here
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "stockflow.gateway.circuit-breaker.minimum-number-of-calls=1000",
                "stockflow.gateway.circuit-breaker.sliding-window-size=1000",
                "stockflow.gateway.retry.first-backoff=10ms",
                "stockflow.gateway.retry.max-backoff=20ms",
                "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1s"
        })
@AutoConfigureWebTestClient
@AutoConfigureWireMock(port = 0)
class RetryTimeoutGatewayTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtService jwtService;

    private String userToken() {
        return jwtService.generateToken("user-1", List.of("ROLE_USER"));
    }

    @Test
    @DisplayName("A GET that fails once with 503 is retried and the client gets the 200")
    void safeRequestIsRetriedAfterTransientFailure() {
        stubFor(get(urlEqualTo("/api/v1/categories")).inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/api/v1/categories")).inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE).withBody("[]")));

        webTestClient.get().uri("/api/v1/categories")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken())
                .exchange()
                .expectStatus().isOk();

        verify(exactly(2), getRequestedFor(urlEqualTo("/api/v1/categories")));
    }

    @Test
    @DisplayName("Retries are bounded: a GET that always fails is sent 3 times, then 503")
    void retriesStopAfterTheConfiguredLimit() {
        stubFor(get(urlEqualTo("/api/v1/products")).willReturn(aResponse().withStatus(503)));

        webTestClient.get().uri("/api/v1/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        verify(exactly(3), getRequestedFor(urlEqualTo("/api/v1/products")));
    }

    @Test
    @DisplayName("A write is never retried, even on 503")
    void unsafeRequestIsSentOnce() {
        String admin = jwtService.generateToken("admin-1", List.of("ROLE_ADMIN", "ROLE_USER"));
        stubFor(post(urlEqualTo("/api/v1/warehouses")).willReturn(aResponse().withStatus(503)));

        webTestClient.post().uri("/api/v1/warehouses")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"code\":\"WH-R\",\"name\":\"R\"}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        verify(exactly(1), postRequestedFor(urlEqualTo("/api/v1/warehouses")));
    }

    @Test
    @DisplayName("A downstream slower than the response timeout ends in 503 after bounded attempts")
    void slowDownstreamTimesOutAndStopsRetrying() {
        stubFor(get(urlEqualTo("/api/v1/products/7")).willReturn(aResponse().withStatus(200).withFixedDelay(1500)));

        webTestClient.get().uri("/api/v1/products/7")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        verify(exactly(3), getRequestedFor(urlEqualTo("/api/v1/products/7")));
    }
}
