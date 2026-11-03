package dev.stockflow.gateway.config;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * A web filter (not a gateway filter) so that requests with no matching route get an ID too.
 * Gives every request one X-Request-Id, forwards it downstream and returns it to the client, including on
 * responses the gateway produces itself (401, 429, 503). A client ID is kept only if it is a short, plain token,
 * so a hostile value cannot reach logs or response headers.
 */
@Component
public class RequestIdFilter implements WebFilter, Ordered {

    public static final String HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTRIBUTE = "stockflow.requestId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String id = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();

        exchange.getAttributes().put(REQUEST_ID_ATTRIBUTE, id);
        ServerHttpRequest request = exchange.getRequest().mutate().headers(h -> h.set(HEADER, id)).build();
        // beforeCommit sets it once, even if the downstream sent its own X-Request-Id
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, id);
            return Mono.empty();
        });
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
