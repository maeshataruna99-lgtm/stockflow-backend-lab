package dev.stockflow.gateway.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.stockflow.gateway.security.JwtService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class LoadBalancingGatewayTest {

    @RegisterExtension
    static WireMockExtension instanceA = WireMockExtension.newInstance()
            .options(options().dynamicPort()).build();

    @RegisterExtension
    static WireMockExtension instanceB = WireMockExtension.newInstance()
            .options(options().dynamicPort()).build();

    @DynamicPropertySource
    static void twoCatalogInstances(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.CATALOG-SERVICE[0].uri", instanceA::baseUrl);
        registry.add("spring.cloud.discovery.client.simple.instances.CATALOG-SERVICE[1].uri", instanceB::baseUrl);
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtService jwtService;

    @Test
    void roundRobinSpreadsRequestsEvenlyAcrossTwoInstances() {
        String token = jwtService.generateToken("user-1", List.of("ROLE_USER"));
        instanceA.stubFor(get(urlEqualTo("/api/v1/products")).willReturn(aResponse().withStatus(200).withBody("[]")));
        instanceB.stubFor(get(urlEqualTo("/api/v1/products")).willReturn(aResponse().withStatus(200).withBody("[]")));

        for (int i = 0; i < 10; i++) {
            webTestClient.get().uri("/api/v1/products")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectStatus().isOk();
        }

        instanceA.verify(exactly(5), getRequestedFor(urlEqualTo("/api/v1/products")));
        instanceB.verify(exactly(5), getRequestedFor(urlEqualTo("/api/v1/products")));
    }
}
