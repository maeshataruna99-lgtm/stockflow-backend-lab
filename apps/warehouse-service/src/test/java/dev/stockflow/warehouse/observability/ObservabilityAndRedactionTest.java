package dev.stockflow.warehouse.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.stockflow.warehouse.AbstractWarehouseIntegrationTest;
import dev.stockflow.warehouse.TestTokens;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@AutoConfigureMockMvc
@AutoConfigureObservability
@TestPropertySource(properties = {
        "management.endpoints.web.exposure.include=health,info,metrics",
        "management.tracing.sampling.probability=1.0"
})
class ObservabilityAndRedactionTest extends AbstractWarehouseIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestCorrelationFilter correlationFilter;

    private MockMvc rawMockMvc;

    @BeforeEach
    void setUp() {
        correlationFilter.clearRecentStructuredLogs();
        rawMockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private static String userBearer() {
        return "Bearer " + TestTokens.token("user-obs", List.of("ROLE_USER"));
    }

    private static String adminBearer() {
        return "Bearer " + TestTokens.token("admin-obs", List.of("ROLE_ADMIN", "ROLE_USER"));
    }

    @Test
    @DisplayName("Actuator metrics endpoint requires ROLE_ADMIN when exposed; /actuator/env remains denied")
    void actuatorMetricsRequiresAdminAndEnvStaysDenied() throws Exception {
        rawMockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());

        rawMockMvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, userBearer()))
                .andExpect(status().isForbidden());

        rawMockMvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());

        rawMockMvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("RequestCorrelationFilter propagates X-Request-Id and W3C traceparent into response and structured logs with redacted secrets")
    void correlationFilterPropagatesTraceAndRedactsSecrets() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String parentSpanId = "00f067aa0ba902b7";
        String requestId = "req-phase10-correlation-01";
        String secretToken = TestTokens.token("user-obs", List.of("ROLE_USER"));
        String webhookSig = "sha256=abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

        mockMvc.perform(get("/api/v1/warehouses?password=mySuperSecretPassword123")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secretToken)
                        .header("X-Webhook-Signature", webhookSig)
                        .header("X-Request-Id", requestId)
                        .header("traceparent", "00-" + traceId + "-" + parentSpanId + "-01"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", requestId))
                .andExpect(header().string("X-Trace-Id", traceId));

        List<String> logs = correlationFilter.recentStructuredLogs();
        assertThat(logs).isNotEmpty();
        String lastLog = logs.get(logs.size() - 1);

        assertThat(lastLog)
                .contains("event=http_request")
                .contains("service=warehouse-service")
                .contains("requestId=" + requestId)
                .contains("traceId=" + traceId)
                .contains("method=GET")
                .contains("path=/api/v1/warehouses")
                .contains("status=200")
                .contains("password=[REDACTED]")
                .doesNotContain(secretToken)
                .doesNotContain(webhookSig)
                .doesNotContain("mySuperSecretPassword123");
    }

    @Test
    @DisplayName("SensitiveDataRedactor redacts headers, JSON fields, query params, Bearer tokens, and HMAC signatures")
    void sensitiveDataRedactorCoversAllRequiredPatterns() {
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig",
                "X-Webhook-Signature", "sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "Cookie", "session=secret-cookie",
                "X-Request-Id", "req-safe-123"
        );
        Map<String, String> redacted = SensitiveDataRedactor.redactMap(headers);
        assertThat(redacted.get("Authorization")).isEqualTo("[REDACTED]");
        assertThat(redacted.get("X-Webhook-Signature")).isEqualTo("[REDACTED]");
        assertThat(redacted.get("Cookie")).isEqualTo("[REDACTED]");
        assertThat(redacted.get("X-Request-Id")).isEqualTo("req-safe-123");

        String json = "{\"username\":\"root\",\"password\":\"root_demo_password_123\",\"accessToken\":\"jwt-123\",\"secret\":\"top-secret\"}";
        String redactedJson = SensitiveDataRedactor.redactText(json);
        assertThat(redactedJson)
                .contains("\"username\":\"root\"")
                .contains("\"password\":\"[REDACTED]\"")
                .contains("\"accessToken\":\"[REDACTED]\"")
                .contains("\"secret\":\"[REDACTED]\"")
                .doesNotContain("root_demo_password_123")
                .doesNotContain("jwt-123")
                .doesNotContain("top-secret");
    }
}
