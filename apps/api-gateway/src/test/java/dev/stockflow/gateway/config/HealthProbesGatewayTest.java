package dev.stockflow.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The probes are reachable without a token: an orchestrator or a load balancer has none. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@AutoConfigureWireMock(port = 0)
class HealthProbesGatewayTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("Liveness and readiness answer 200 UP without a token")
    void probesAreOpen() {
        for (String probe : new String[] {"liveness", "readiness"}) {
            webTestClient.get().uri("/actuator/health/" + probe).exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.status").isEqualTo("UP");
        }
    }
}
