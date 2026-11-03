package dev.stockflow.gateway.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;

import dev.stockflow.gateway.security.JwtService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import com.github.tomakehurst.wiremock.client.WireMock;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@AutoConfigureWireMock(port = 0)
class ApiVersioningGatewayTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void resetWireMock() {
        WireMock.reset();
    }

    @AfterEach
    void resetRateLimiter() {
        TestRateLimiterConfig.EXCEED_QUOTA.set(false);
    }

    private String bearer(String... roles) {
        return "Bearer " + jwtService.generateToken("user-1", List.of(roles));
    }

    private void stubProducts() {
        stubFor(get(urlPathMatching("/api/v1/products")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE).withBody("[]")));
    }

    @Test
    @DisplayName("An unversioned path still works: it reaches the v1 endpoint and is marked deprecated")
    void unversionedPathIsServedByV1AndMarkedDeprecated() {
        stubProducts();

        webTestClient.get().uri("/api/products?page=0&size=5")
                .header(HttpHeaders.AUTHORIZATION, bearer("ROLE_USER"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Deprecation", "true")
                .expectHeader().valueEquals("Link", "</api/v1/products>; rel=\"successor-version\"");

        verify(exactly(1), getRequestedFor(urlEqualTo("/api/v1/products?page=0&size=5")));
    }

    @Test
    @DisplayName("A v1 path is unchanged and carries no deprecation header")
    void versionedPathIsNotMarkedDeprecated() {
        stubProducts();

        webTestClient.get().uri("/api/v1/products")
                .header(HttpHeaders.AUTHORIZATION, bearer("ROLE_USER"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Deprecation")
                .expectHeader().doesNotExist("Link");
    }

    @Test
    @DisplayName("The unversioned alias is authenticated exactly like v1")
    void unversionedPathRequiresAToken() {
        webTestClient.get().uri("/api/products")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(exactly(0), getRequestedFor(urlPathMatching("/api/.*")));
    }

    @Test
    @DisplayName("The unversioned alias is authorized exactly like v1: a read-only role cannot write")
    void unversionedWriteNeedsAdminRole() {
        webTestClient.post().uri("/api/products")
                .header(HttpHeaders.AUTHORIZATION, bearer("ROLE_USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("The unversioned alias shares the v1 rate limit")
    void unversionedPathIsRateLimited() {
        TestRateLimiterConfig.EXCEED_QUOTA.set(true);

        webTestClient.get().uri("/api/products")
                .header(HttpHeaders.AUTHORIZATION, bearer("ROLE_USER"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("An unsupported version is a 404 problem and nothing is forwarded")
    void unsupportedVersionIsNotFound() {
        webTestClient.get().uri("/api/v9/products")
                .header(HttpHeaders.AUTHORIZATION, bearer("ROLE_USER"))
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);

        verify(exactly(0), getRequestedFor(urlPathMatching("/api/.*")));
    }
}
