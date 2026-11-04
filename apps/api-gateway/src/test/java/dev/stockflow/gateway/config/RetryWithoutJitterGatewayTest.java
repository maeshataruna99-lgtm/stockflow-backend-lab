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

/** The same experiment with jitter 0: every request waits the same time, the synchronised retries jitter exists to avoid. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "stockflow.gateway.circuit-breaker.minimum-number-of-calls=1000",
                "stockflow.gateway.circuit-breaker.sliding-window-size=1000",
                "stockflow.gateway.retry.first-backoff=200ms",
                "stockflow.gateway.retry.max-backoff=2s",
                "stockflow.gateway.retry.jitter=0",
                "spring.cloud.gateway.server.webflux.httpclient.response-timeout=2s"
        })
@AutoConfigureWebTestClient
@AutoConfigureWireMock(port = 0)
class RetryWithoutJitterGatewayTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtService jwtService;

    @Test
    @DisplayName("With jitter 0 the first waits are all about the backoff (200 ms), with only processing noise")
    void waitsAreConstant() {
        RetryJitterTestSupport.Gaps gaps = RetryJitterTestSupport.collect(webTestClient, jwtService, 10);

        System.out.println("jitter 0, first waits (ms): " + gaps.first() + "  second waits (ms): " + gaps.second());
        assertThat(gaps.first()).allSatisfy(g -> assertThat(g).isBetween(200L - 10, 200L + 60));
        assertThat(RetryJitterTestSupport.spread(gaps.first())).as("spread of the first waits").isLessThan(50);
    }
}
