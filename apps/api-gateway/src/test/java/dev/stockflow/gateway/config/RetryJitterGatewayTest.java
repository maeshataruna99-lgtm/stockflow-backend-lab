package dev.stockflow.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stockflow.gateway.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The wait between the gateway's retries has random jitter, so many clients that failed together do not all retry at
 * the same instant. Observed from outside: WireMock's journal says when each attempt arrived. Backoff 200 ms doubling,
 * jitter 0.5, so the first wait lies in 200..300 ms and the second in 200..600 ms (plus a few ms of processing). The library
 * clamps each jittered wait between the first backoff and the maximum backoff, so jitter never shortens a wait below
 * 200 ms; that is why the first wait is not 100..300.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "stockflow.gateway.circuit-breaker.minimum-number-of-calls=1000",
                "stockflow.gateway.circuit-breaker.sliding-window-size=1000",
                "stockflow.gateway.retry.first-backoff=200ms",
                "stockflow.gateway.retry.max-backoff=2s",
                "stockflow.gateway.retry.jitter=0.5",
                "spring.cloud.gateway.server.webflux.httpclient.response-timeout=2s"
        })
@AutoConfigureWebTestClient
@AutoConfigureWireMock(port = 0)
class RetryJitterGatewayTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtService jwtService;

    @Test
    @DisplayName("With jitter 0.5 the waits between retries differ from request to request and stay inside the bounds")
    void waitsAreSpreadWithinBounds() {
        RetryJitterTestSupport.Gaps gaps = RetryJitterTestSupport.collect(webTestClient, jwtService, 10);

        System.out.println("jitter 0.5, first waits (ms): " + gaps.first() + "  second waits (ms): " + gaps.second());
        assertThat(gaps.first()).allSatisfy(g -> assertThat(g).isBetween(200L - 10, 300L + 60));
        assertThat(gaps.second()).allSatisfy(g -> assertThat(g).isBetween(200L - 10, 600L + 60));
        assertThat(RetryJitterTestSupport.spread(gaps.first())).as("spread of the first waits").isGreaterThan(40);
        assertThat(gaps.first().stream().distinct().count()).as("distinct first waits").isGreaterThanOrEqualTo(6);
    }
}
