package dev.stockflow.warehouse.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exports OpenTelemetry spans to a Zipkin v2 HTTP collector (such as Jaeger on port 9411)
 * using the JDK HttpClient, with zero extra dependencies and automatic sensitive-tag redaction.
 */
@Configuration
@ConditionalOnProperty(name = "stockflow.tracing.zipkin-endpoint")
public class ZipkinSpanExporterConfig {

    @Bean
    public SpanExporter zipkinHttpSpanExporter(
            @Value("${stockflow.tracing.zipkin-endpoint}") String endpoint,
            @Value("${spring.application.name:warehouse-service}") String serviceName,
            ObjectMapper objectMapper) {
        return new ZipkinHttpSpanExporter(URI.create(endpoint), serviceName, objectMapper);
    }

    @Bean
    public SpanProcessor zipkinSimpleSpanProcessor(SpanExporter zipkinHttpSpanExporter) {
        return SimpleSpanProcessor.create(zipkinHttpSpanExporter);
    }

    public static final class ZipkinHttpSpanExporter implements SpanExporter {

        private final URI endpoint;
        private final String serviceName;
        private final ObjectMapper objectMapper;
        private final HttpClient httpClient;

        public ZipkinHttpSpanExporter(URI endpoint, String serviceName, ObjectMapper objectMapper) {
            this.endpoint = endpoint;
            this.serviceName = serviceName;
            this.objectMapper = objectMapper;
            this.httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            if (spans == null || spans.isEmpty()) {
                return CompletableResultCode.ofSuccess();
            }
            try {
                byte[] payload = toZipkinJson(spans, serviceName, objectMapper);
                HttpRequest request = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                        .build();
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                return response.statusCode() >= 200 && response.statusCode() < 300
                        ? CompletableResultCode.ofSuccess()
                        : CompletableResultCode.ofFailure();
            } catch (Exception ignored) {
                // Tracing export is best-effort so a collector outage never breaks a business request.
                return CompletableResultCode.ofFailure();
            }
        }

        public static byte[] toZipkinJson(Collection<SpanData> spans, String defaultServiceName,
                                          ObjectMapper objectMapper) throws Exception {
            List<Map<String, Object>> list = new ArrayList<>(spans.size());
            for (SpanData span : spans) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("traceId", span.getTraceId());
                item.put("id", span.getSpanId());
                if (span.getParentSpanContext().isValid()) {
                    item.put("parentId", span.getParentSpanId());
                }
                item.put("name", span.getName());
                long startMicros = Math.max(1L, span.getStartEpochNanos() / 1_000L);
                long durationMicros = Math.max(1L, (span.getEndEpochNanos() - span.getStartEpochNanos()) / 1_000L);
                item.put("timestamp", startMicros);
                item.put("duration", durationMicros);
                item.put("kind", mapKind(span));
                item.put("localEndpoint", Map.of("serviceName", defaultServiceName));

                Map<String, String> tags = new LinkedHashMap<>();
                span.getAttributes().forEach((key, value) -> {
                    if (key != null && value != null) {
                        tags.put(key.getKey(), SensitiveDataRedactor.redactKeyValue(key.getKey(), String.valueOf(value)));
                    }
                });
                item.put("tags", tags);
                list.add(item);
            }
            return objectMapper.writeValueAsBytes(list);
        }

        private static String mapKind(SpanData span) {
            return switch (span.getKind()) {
                case SERVER -> "SERVER";
                case CLIENT -> "CLIENT";
                case PRODUCER -> "PRODUCER";
                case CONSUMER -> "CONSUMER";
                default -> null;
            };
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
