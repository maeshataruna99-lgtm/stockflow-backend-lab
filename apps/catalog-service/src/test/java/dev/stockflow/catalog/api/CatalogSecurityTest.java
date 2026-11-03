package dev.stockflow.catalog.api;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.stockflow.catalog.AbstractCatalogIntegrationTest;
import dev.stockflow.catalog.TestTokens;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The service enforces the JWT itself (ADR 0007), so a caller that reaches it without going through the
 * gateway still needs a valid token. This MockMvc is built by hand: it carries no default Authorization header.
 */
class CatalogSecurityTest extends AbstractCatalogIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void buildMockMvcWithoutDefaultToken() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String user() {
        return bearer(TestTokens.token("user-1", List.of("ROLE_USER")));
    }

    private static String admin() {
        return bearer(TestTokens.token("admin-1", List.of("ROLE_ADMIN", "ROLE_USER")));
    }

    @Test
    @DisplayName("No token: 401 problem+json")
    void missingTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    @DisplayName("A token signed with another key is rejected")
    void wrongSignatureIsUnauthorized() throws Exception {
        String forged = TestTokens.custom("admin-1", List.of("ROLE_ADMIN"), TestTokens.ISSUER,
                Instant.now().plusSeconds(600), "another-signing-key-that-is-long-enough-0123456789");

        mockMvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(forged)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("An expired token is rejected")
    void expiredTokenIsUnauthorized() throws Exception {
        String expired = TestTokens.custom("user-1", List.of("ROLE_USER"), TestTokens.ISSUER,
                Instant.now().minusSeconds(60), TestTokens.SECRET);

        mockMvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(expired)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A token from another issuer is rejected")
    void wrongIssuerIsUnauthorized() throws Exception {
        String other = TestTokens.custom("user-1", List.of("ROLE_USER"), "someone-else",
                Instant.now().plusSeconds(600), TestTokens.SECRET);

        mockMvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(other)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ROLE_USER can read")
    void userCanRead() throws Exception {
        mockMvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, user()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ROLE_USER cannot write: 403 problem+json")
    void userCannotWrite() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SEC-1\",\"name\":\"x\",\"categoryId\":1,\"unitId\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    @DisplayName("ROLE_ADMIN can write")
    void adminCanWrite() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SEC-ADMIN-" + System.nanoTime() + "\",\"name\":\"x\",\"categoryId\":1,\"unitId\":1}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("A valid token without roles is forbidden even for reads")
    void tokenWithoutRolesIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.token("nobody", List.of()))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Identity headers set by a caller do not raise privileges")
    void roleHeaderIsIgnored() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, user())
                        .header("X-User-Roles", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SEC-2\",\"name\":\"x\",\"categoryId\":1,\"unitId\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Health and the API documents stay open: the container health check has no token")
    void healthAndDocsArePublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Metrics need an admin token when the lab override exposes them")
    void metricsNeedAnAdmin() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, user()))
                .andExpect(status().isForbidden());
        // not exposed in the test profile, so an admin gets past security and meets a 404
        mockMvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Anything else is denied, including actuator endpoints that are not health or info")
    void unknownPathsAreDeniedWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/something-else")).andExpect(status().isUnauthorized());
    }
}
