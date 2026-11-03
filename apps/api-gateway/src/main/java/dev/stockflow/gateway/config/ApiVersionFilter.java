package dev.stockflow.gateway.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Backward compatibility for clients that call an API path without a version (/api/products). Such a path is
 * rewritten to v1 before routing, so authentication, rate limit and circuit breaker treat it exactly like
 * /api/v1/products, and the response is marked deprecated with a link to the versioned path.
 * Versioned paths (/api/v1/..., /api/v9/...) are left alone: v1 is served, an unknown version has no route.
 */
@Component
public class ApiVersionFilter implements WebFilter, Ordered {

    // /api/<something> where <something> is not a version segment such as v1 or v12
    private static final Pattern UNVERSIONED = Pattern.compile("^/api/(?!v\\d+(?:/|$))(.+)$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Matcher m = UNVERSIONED.matcher(exchange.getRequest().getPath().value());
        if (!m.matches()) {
            return chain.filter(exchange);
        }
        String v1Path = "/api/v1/" + m.group(1);
        ServerHttpRequest rewritten = exchange.getRequest().mutate().path(v1Path).build();
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set("Deprecation", "true");
            exchange.getResponse().getHeaders().set("Link", "<" + v1Path + ">; rel=\"successor-version\"");
            return Mono.empty();
        });
        return chain.filter(exchange.mutate().request(rewritten).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2; // after the request ID, before routing
    }
}
