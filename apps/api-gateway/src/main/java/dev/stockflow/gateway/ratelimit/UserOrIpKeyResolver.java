package dev.stockflow.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

@Component("userOrIpKeyResolver")
public class UserOrIpKeyResolver implements KeyResolver {

    public static final String ATTR_USER_ID = "stockflow.authenticated.user";

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        // 1. First check verified attribute set by JwtAuthenticationFilter
        String verifiedUserId = exchange.getAttribute(ATTR_USER_ID);
        if (verifiedUserId != null && !verifiedUserId.isBlank()) {
            return Mono.just("user:" + verifiedUserId);
        }

        // 2. Check sanitized request header set by JwtAuthenticationFilter
        String headerUserId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        if (headerUserId != null && !headerUserId.isBlank()) {
            return Mono.just("user:" + headerUserId);
        }

        // 3. Fallback to client remote IP (Safeguard: ignore arbitrary unverified client headers)
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            return Mono.just("ip:" + remoteAddress.getAddress().getHostAddress());
        }

        return Mono.just("ip:unknown");
    }
}
