package dev.stockflow.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Writes the one error shape of the gateway: RFC 7807 problem+json with the request ID, never an internal message. */
@Component
public class ProblemWriter {

    private final ObjectMapper objectMapper;

    public ProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, String detail, String typeUrn) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(titleOf(status));
        if (typeUrn != null) {
            problem.setType(URI.create(typeUrn));
        }
        problem.setProperty("requestId", exchange.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));

        try {
            DataBuffer buffer = response.bufferFactory().wrap(objectMapper.writeValueAsBytes(problem));
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return response.setComplete();
        }
    }

    public static String titleOf(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return known != null ? known.getReasonPhrase() : "Error";
    }

    /** Fixed text per status: an exception message may hold hosts, queries or credentials and is never used. */
    public static String detailOf(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "The request could not be processed.";
            case 404 -> "No route matches this request.";
            case 405 -> "This method is not allowed for this route.";
            case 429 -> "Too many requests. Slow down and retry later.";
            case 502 -> "The gateway could not get a valid answer from the downstream service.";
            case 503 -> "The downstream service is unavailable. Retry later.";
            case 504 -> "The downstream service did not answer in time.";
            default -> status.is5xxServerError() ? "Unexpected gateway error." : "The request was rejected.";
        };
    }
}
