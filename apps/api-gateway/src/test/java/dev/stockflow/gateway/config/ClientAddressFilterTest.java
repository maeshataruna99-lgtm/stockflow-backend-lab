package dev.stockflow.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class ClientAddressFilterTest {

    private final ClientAddressFilter filter = new ClientAddressFilter();

    private ServerWebExchange run(MockServerHttpRequest.BaseBuilder<?> request) {
        AtomicReference<ServerWebExchange> passedOn = new AtomicReference<>();
        filter.filter(MockServerWebExchange.from(request), exchange -> {
            passedOn.set(exchange);
            return Mono.empty();
        }).block();
        return passedOn.get();
    }

    @Test
    @DisplayName("The TCP peer address becomes X-Client-Ip")
    void peerAddressIsPassedOn() {
        ServerWebExchange passed = run(MockServerHttpRequest.get("/api/v1/products")
                .remoteAddress(new InetSocketAddress("10.1.2.3", 51000)));

        assertThat(passed.getRequest().getHeaders().get("X-Client-Ip")).containsExactly("10.1.2.3");
    }

    @Test
    @DisplayName("A value sent by the client is replaced, and without a peer address the header is absent")
    void clientValueIsNeverTrusted() {
        ServerWebExchange replaced = run(MockServerHttpRequest.get("/api/v1/products")
                .header("X-Client-Ip", "6.6.6.6").remoteAddress(new InetSocketAddress("10.1.2.3", 51000)));
        ServerWebExchange noPeer = run(MockServerHttpRequest.get("/api/v1/products").header("X-Client-Ip", "6.6.6.6"));

        assertThat(replaced.getRequest().getHeaders().get("X-Client-Ip")).containsExactly("10.1.2.3");
        assertThat(noPeer.getRequest().getHeaders().containsKey("X-Client-Ip")).isFalse();
    }
}
