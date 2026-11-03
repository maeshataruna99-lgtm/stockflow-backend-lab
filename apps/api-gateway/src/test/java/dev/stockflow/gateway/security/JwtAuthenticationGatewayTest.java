package dev.stockflow.gateway.security;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@AutoConfigureWireMock(port = 0)
class JwtAuthenticationGatewayTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JwtProperties jwtProperties;

    @Test
    @DisplayName("1. GET with valid ROLE_USER token succeeds (200) and propagates identity headers")
    void readOperationWithUserRoleSucceedsAndPropagatesHeaders() {
        String token = jwtService.generateToken("john-doe", List.of("ROLE_USER"));

        stubFor(get(urlEqualTo("/api/v1/products"))
                .withHeader("X-User-Id", equalTo("john-doe"))
                .withHeader("X-User-Roles", equalTo("ROLE_USER"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("[{\"id\":1,\"sku\":\"PROD-01\"}]")
                        .withStatus(200)));

        webTestClient.get()
                .uri("/api/v1/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].sku").isEqualTo("PROD-01");
    }

    @Test
    @DisplayName("2. POST with ROLE_USER token is rejected with 403 Forbidden (requires ROLE_ADMIN)")
    void mutatingOperationWithUserRoleIsForbidden() {
        String token = jwtService.generateToken("john-doe", List.of("ROLE_USER"));

        webTestClient.post()
                .uri("/api/v1/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"sku\":\"NEW-SKU\",\"name\":\"New Item\"}")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.title").isEqualTo("Forbidden")
                .jsonPath("$.detail").value(val -> ((String) val).contains("Admin role required"));
    }

    @Test
    @DisplayName("3. POST with ROLE_ADMIN token succeeds (201) and propagates admin identity")
    void mutatingOperationWithAdminRoleSucceeds() {
        String token = jwtService.generateToken("root-admin", List.of("ROLE_ADMIN", "ROLE_USER"));

        stubFor(post(urlEqualTo("/api/v1/products"))
                .withHeader("X-User-Id", equalTo("root-admin"))
                .withHeader("X-User-Roles", equalTo("ROLE_ADMIN,ROLE_USER"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"id\":99,\"sku\":\"NEW-SKU\"}")
                        .withStatus(201)));

        webTestClient.post()
                .uri("/api/v1/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"sku\":\"NEW-SKU\",\"name\":\"New Item\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isEqualTo(99);
    }

    @Test
    @DisplayName("4. Request missing Authorization header returns 401 Unauthorized")
    void missingAuthorizationHeaderReturns401() {
        webTestClient.get()
                .uri("/api/v1/products")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.title").isEqualTo("Unauthorized")
                .jsonPath("$.detail").isEqualTo("Missing or invalid Authorization header");
    }

    @Test
    @DisplayName("5. Request with expired token returns 401 Unauthorized")
    void expiredTokenReturns401() {
        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        String expiredToken = Jwts.builder()
                .subject("expired-user")
                .claim("roles", List.of("ROLE_USER"))
                .issuer(jwtProperties.getIssuer())
                .issuedAt(new Date(System.currentTimeMillis() - 100000))
                .expiration(new Date(System.currentTimeMillis() - 50000))
                .signWith(key)
                .compact();

        webTestClient.get()
                .uri("/api/v1/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.title").isEqualTo("Unauthorized")
                .jsonPath("$.detail").value(val -> ((String) val).contains("expired"));
    }

    @Test
    @DisplayName("6. Request with tampered signature returns 401 Unauthorized")
    void tamperedTokenReturns401() {
        String validToken = jwtService.generateToken("legit-user", List.of("ROLE_USER"));
        String tamperedToken = validToken.substring(0, validToken.length() - 5) + "XYZ12";

        webTestClient.get()
                .uri("/api/v1/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tamperedToken)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.title").isEqualTo("Unauthorized");
    }

    @Test
    @DisplayName("7. Anti-spoofing: external client cannot forge X-User-Id header")
    void externalHeaderSpoofingIsSanitized() {
        String token = jwtService.generateToken("real-user", List.of("ROLE_USER"));

        stubFor(get(urlEqualTo("/api/v1/products"))
                .withHeader("X-User-Id", equalTo("real-user")) // Downstream must receive sanitized real-user, not hacker
                .willReturn(aResponse()
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("[]")
                        .withStatus(200)));

        webTestClient.get()
                .uri("/api/v1/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Id", "hacker-attempting-spoof")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("8. Demo login with valid root credentials returns 200 with JWT access token")
    void demoLoginWithRootCredentialsSucceeds() {
        webTestClient.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"root\",\"password\":\"root_demo_password_123\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isNotEmpty()
                .jsonPath("$.tokenType").isEqualTo("Bearer")
                .jsonPath("$.expiresIn").isEqualTo(jwtProperties.getExpirationSeconds());
    }

    @Test
    @DisplayName("9. Demo login with invalid credentials returns 401 Unauthorized")
    void demoLoginWithWrongPasswordReturns401() {
        webTestClient.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"root\",\"password\":\"wrong-password\"}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.detail").isEqualTo("Invalid username or password");
    }
}
