package dev.stockflow.warehouse.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Propagates X-Request-Id, binds requestId/traceId/spanId into MDC, tags the active OpenTelemetry span,
 * and emits a redacted structured access log line for every HTTP request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);

    private final List<String> recentStructuredLogs = new CopyOnWriteArrayList<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();

        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put("requestId", requestId);

        Span currentSpan = Span.current();
        SpanContext spanContext = currentSpan.getSpanContext();
        String traceId = spanContext.isValid() ? spanContext.getTraceId() : extractTraceIdFromHeader(request.getHeader("traceparent"));
        String spanId = spanContext.isValid() ? spanContext.getSpanId() : "";

        if (spanContext.isValid()) {
            currentSpan.setAttribute("http.request_id", requestId);
        }
        if (traceId != null && !traceId.isEmpty()) {
            MDC.put("traceId", traceId);
            response.setHeader(TRACE_ID_HEADER, traceId);
        }
        if (spanId != null && !spanId.isEmpty()) {
            MDC.put("spanId", spanId);
        }

        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            Span afterSpan = Span.current();
            if ((traceId == null || traceId.isEmpty()) && afterSpan.getSpanContext().isValid()) {
                traceId = afterSpan.getSpanContext().getTraceId();
                spanId = afterSpan.getSpanContext().getSpanId();
            }
            Map<String, String> redactedHeaders = extractAndRedactHeaders(request);
            String query = SensitiveDataRedactor.redactText(request.getQueryString());
            String line = String.format(
                    "event=http_request service=warehouse-service requestId=%s traceId=%s spanId=%s method=%s path=%s query=%s status=%d durationMs=%d headers=%s",
                    requestId,
                    traceId == null || traceId.isEmpty() ? "none" : traceId,
                    spanId == null || spanId.isEmpty() ? "none" : spanId,
                    request.getMethod(),
                    request.getRequestURI(),
                    query == null ? "" : query,
                    response.getStatus(),
                    durationMs,
                    redactedHeaders
            );
            recordStructuredLog(line);
            log.info(line);
            MDC.remove("requestId");
            MDC.remove("traceId");
            MDC.remove("spanId");
        }
    }

    private static String extractTraceIdFromHeader(String traceparent) {
        if (traceparent == null) {
            return "";
        }
        String[] parts = traceparent.trim().split("-");
        if (parts.length == 4 && parts[1].length() == 32) {
            return parts[1];
        }
        return "";
    }

    private static Map<String, String> extractAndRedactHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (request.getHeaderNames() == null) {
            return headers;
        }
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, SensitiveDataRedactor.redactKeyValue(name, request.getHeader(name)));
        }
        return headers;
    }

    private void recordStructuredLog(String line) {
        if (recentStructuredLogs.size() >= 200) {
            recentStructuredLogs.remove(0);
        }
        recentStructuredLogs.add(line);
    }

    public List<String> recentStructuredLogs() {
        return List.copyOf(recentStructuredLogs);
    }

    public void clearRecentStructuredLogs() {
        recentStructuredLogs.clear();
    }
}
