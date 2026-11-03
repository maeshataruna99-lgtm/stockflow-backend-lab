package dev.stockflow.gateway.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class UserOrIpKeyResolverTest {

    private final UserOrIpKeyResolver keyResolver = new UserOrIpKeyResolver();

    @Test
    @DisplayName("Resolves authenticated user from exchange attribute")
    void resolvesFromAuthenticatedUserAttribute() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/products")
                .remoteAddress(new InetSocketAddress("192.168.1.100", 8080))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(UserOrIpKeyResolver.ATTR_USER_ID, "verified-root");

        String resolved = keyResolver.resolve(exchange).block();
        assertThat(resolved).isEqualTo("user:verified-root");
    }

    @Test
    @DisplayName("Resolves authenticated user from X-User-Id header")
    void resolvesFromVerifiedHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/products")
                .header("X-User-Id", "verified-user")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 8080))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String resolved = keyResolver.resolve(exchange).block();
        assertThat(resolved).isEqualTo("user:verified-user");
    }

    @Test
    @DisplayName("Falls back to client remote IP when unauthenticated")
    void fallsBackToClientRemoteIp() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/products")
                .remoteAddress(new InetSocketAddress("203.0.113.195", 54321))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String resolved = keyResolver.resolve(exchange).block();
        assertThat(resolved).isEqualTo("ip:203.0.113.195");
    }

    @Test
    @DisplayName("Safeguard: ignores spoofed unverified headers like X-Forwarded-For")
    void ignoresArbitraryClientHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/products")
                .header("X-Forwarded-For", "1.1.1.1")
                .header("X-Client-Id", "spoofed-client")
                .remoteAddress(new InetSocketAddress("198.51.100.25", 8080))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String resolved = keyResolver.resolve(exchange).block();
        assertThat(resolved).isEqualTo("ip:198.51.100.25");
    }
}
