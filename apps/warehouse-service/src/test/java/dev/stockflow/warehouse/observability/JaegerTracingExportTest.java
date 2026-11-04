package dev.stockflow.warehouse.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.stockflow.warehouse.AbstractWarehouseIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.DockerImageName;

@AutoConfigureMockMvc
@AutoConfigureObservability
class JaegerTracingExportTest extends AbstractWarehouseIntegrationTest {

    private static final GenericContainer<?> JAEGER;

    static {
        JAEGER = new GenericContainer<>(DockerImageName.parse("jaegertracing/all-in-one:1.62.0"))
                .withEnv("COLLECTOR_ZIPKIN_HOST_PORT", ":9411")
                .withExposedPorts(9411, 16686, 14269)
                .waitingFor(new HttpWaitStrategy().forPort(14269).forPath("/").forStatusCode(200));
        JAEGER.start();
    }

    @DynamicPropertySource
    static void jaegerProperties(DynamicPropertyRegistry registry) {
        String zipkinUrl = "http://" + JAEGER.getHost() + ":" + JAEGER.getMappedPort(9411) + "/api/v2/spans";
        registry.add("stockflow.tracing.zipkin-endpoint", () -> zipkinUrl);
        registry.add("management.tracing.sampling.probability", () -> "1.0");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Distributed trace spans across api-gateway and warehouse-service export to Jaeger and share one traceId with redacted tags")
    void exportsDistributedTraceAcrossGatewayAndWarehouseToJaeger() throws Exception {
        String traceId = "7a9b3c5d1e2f4a6b8c0d1e2f3a4b5c6d";
        String gatewaySpanId = "1122334455667788";
        String requestId = "req-jaeger-lab-50";

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        URI zipkinUri = URI.create("http://" + JAEGER.getHost() + ":" + JAEGER.getMappedPort(9411) + "/api/v2/spans");
        long nowMicros = System.currentTimeMillis() * 1_000L;
        Map<String, Object> gatewaySpan = Map.of(
                "traceId", traceId,
                "id", gatewaySpanId,
                "name", "http get /api/v1/warehouses",
                "kind", "SERVER",
                "timestamp", nowMicros,
                "duration", 4_500L,
                "localEndpoint", Map.of("serviceName", "api-gateway"),
                "tags", Map.of(
                        "http.request_id", requestId,
                        "http.authorization", SensitiveDataRedactor.redactKeyValue("Authorization", "Bearer secret-jwt")
                )
        );
        byte[] gatewayPayload = objectMapper.writeValueAsBytes(List.of(gatewaySpan));
        HttpResponse<String> postResp = httpClient.send(
                HttpRequest.newBuilder(zipkinUri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(gatewayPayload))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(postResp.statusCode()).isBetween(200, 202);

        mockMvc.perform(get("/api/v1/warehouses")
                        .header("X-Request-Id", requestId)
                        .header("traceparent", "00-" + traceId + "-" + gatewaySpanId + "-01"))
                .andExpect(status().isOk());

        URI queryUri = URI.create("http://" + JAEGER.getHost() + ":" + JAEGER.getMappedPort(16686) + "/api/traces/" + traceId);

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    HttpResponse<String> traceResp = httpClient.send(
                            HttpRequest.newBuilder(queryUri).GET().build(),
                            HttpResponse.BodyHandlers.ofString()
                    );
                    assertThat(traceResp.statusCode()).isEqualTo(200);
                    JsonNode root = objectMapper.readTree(traceResp.body());
                    JsonNode data = root.path("data");
                    assertThat(data.isArray()).isTrue();
                    assertThat(data).isNotEmpty();

                    JsonNode trace = data.get(0);
                    assertThat(trace.path("traceID").asText()).isEqualTo(traceId);

                    Set<String> services = new HashSet<>();
                    trace.path("processes").fields().forEachRemaining(
                            entry -> services.add(entry.getValue().asText(""))
                    );
                    trace.path("processes").forEach(
                            procNode -> services.add(procNode.path("serviceName").asText())
                    );
                    assertThat(services).contains("api-gateway", "warehouse-service");

                    JsonNode spans = trace.path("spans");
                    assertThat(spans.size()).isGreaterThanOrEqualTo(2);
                    assertThat(traceResp.body())
                            .contains(requestId)
                            .doesNotContain("secret-jwt");
                });
    }
}
