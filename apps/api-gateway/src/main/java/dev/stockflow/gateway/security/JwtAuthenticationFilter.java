package dev.stockflow.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Collections;
import java.util.List;

@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 1. Skip public endpoints
        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        // 2. Only protect /api/v1/** business routes
        if (!path.startsWith("/api/v1/")) {
            return chain.filter(exchange);
        }

        // 3. Strip any incoming client identity headers to prevent header spoofing
        ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate();
        requestBuilder.headers(httpHeaders -> {
            httpHeaders.remove("X-User-Id");
            httpHeaders.remove("X-User-Roles");
        });

        // 4. Extract Authorization header
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return writeProblemDetail(
                    exchange.getResponse(),
                    HttpStatus.UNAUTHORIZED,
                    "Missing or invalid Authorization header",
                    "urn:stockflow:problem:missing-token"
            );
        }

        String token = authHeader.substring(7).trim();

        // 5. Validate token & extract claims
        Claims claims;
        try {
            claims = jwtService.validateAndExtractClaims(token);
        } catch (JwtException | IllegalArgumentException e) {
            return writeProblemDetail(
                    exchange.getResponse(),
                    HttpStatus.UNAUTHORIZED,
                    "Invalid, expired or tampered JWT token: " + e.getMessage(),
                    "urn:stockflow:problem:invalid-token"
            );
        }

        // 6. Check role authorization matrix
        List<String> roles = extractRoles(claims);
        HttpMethod method = exchange.getRequest().getMethod();

        if (HttpMethod.GET.equals(method)) {
            // Read operations require at least ROLE_USER or ROLE_ADMIN
            if (!hasAnyRole(roles, "ROLE_USER", "ROLE_ADMIN")) {
                return writeProblemDetail(
                        exchange.getResponse(),
                        HttpStatus.FORBIDDEN,
                        "Insufficient role for read operations. Required: ROLE_USER or ROLE_ADMIN",
                        "urn:stockflow:problem:forbidden"
                );
            }
        } else if (isMutatingMethod(method)) {
            // Mutating operations (POST, PATCH, PUT, DELETE) require ROLE_ADMIN
            if (!hasAnyRole(roles, "ROLE_ADMIN")) {
                return writeProblemDetail(
                        exchange.getResponse(),
                        HttpStatus.FORBIDDEN,
                        "Admin role required for mutating operations. Required: ROLE_ADMIN",
                        "urn:stockflow:problem:forbidden"
                );
            }
        }

        // 7. Inject verified context headers for downstream services and exchange attribute for KeyResolver
        exchange.getAttributes().put("stockflow.authenticated.user", claims.getSubject());
        requestBuilder.header("X-User-Id", claims.getSubject());
        requestBuilder.header("X-User-Roles", String.join(",", roles));

        return chain.filter(exchange.mutate().request(requestBuilder.build()).build());
    }

    private boolean isPublicPath(String path) {
        return path.equals("/actuator/health") ||
                path.equals("/actuator/info") ||
                path.startsWith("/api/v1/auth/");
    }

    private boolean isMutatingMethod(HttpMethod method) {
        return HttpMethod.POST.equals(method) ||
                HttpMethod.PATCH.equals(method) ||
                HttpMethod.PUT.equals(method) ||
                HttpMethod.DELETE.equals(method);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRoles(Claims claims) {
        Object rolesObj = claims.get("roles");
        if (rolesObj instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return Collections.emptyList();
    }

    private boolean hasAnyRole(List<String> userRoles, String... requiredRoles) {
        for (String required : requiredRoles) {
            if (userRoles.contains(required)) {
                return true;
            }
        }
        return false;
    }

    private Mono<Void> writeProblemDetail(ServerHttpResponse response, HttpStatus status, String detail, String typeUrn) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create(typeUrn));

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(problem);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return response.setComplete();
        }
    }

    @Override
    public int getOrder() {
        // Run before routing filters
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
