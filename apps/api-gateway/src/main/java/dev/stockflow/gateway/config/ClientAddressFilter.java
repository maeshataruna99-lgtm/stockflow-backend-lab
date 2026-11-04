package dev.stockflow.gateway.config;

import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Tells the service behind the gateway who the client is. The gateway is the edge: no proxy stands in front of it, so
 * the address of the TCP connection is the client. It is sent as X-Client-Ip, replacing anything the client put in that
 * header. The standard X-Forwarded-For is not used on purpose: the gateway drops the Forwarded and X-Forwarded-* headers
 * a client sends (Spring Cloud Gateway's default), and setting trusted-proxies to make it add its own was measured to let
 * a forged X-Forwarded-Host and X-Forwarded-Proto through (guidebook 32).
 */
@Component
public class ClientAddressFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Client-Ip";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return chain.filter(exchange.mutate().request(request -> request.headers(headers -> {
            headers.remove(HEADER);
            if (remote != null && remote.getAddress() != null) {
                headers.set(HEADER, remote.getAddress().getHostAddress());
            }
        })).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
