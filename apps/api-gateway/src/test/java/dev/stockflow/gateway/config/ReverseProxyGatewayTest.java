package dev.stockflow.gateway.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.stockflow.gateway.security.JwtService;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.http.MediaType;

/**
 * The gateway is the reverse proxy in front of the services. What the service behind it sees is what the proxy decides:
 * which client details are passed on, and which of them the client may not choose itself. The downstream here is
 * WireMock, which records the request exactly as it arrived.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWireMock(port = 0)
class ReverseProxyGatewayTest {

    static {
        // the JDK client refuses to send Upgrade (and Host, Connection) unless allowed, before its first use
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host,connection,upgrade");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private record Received(int status, HttpHeaders headers, String url) {
    }

    /**
     * Sent with the JDK HTTP client to the running server, not with WebTestClient: WebTestClient is bound to the
     * application in mock mode here (the request class was MockServerHttpRequest), which has no TCP connection and so no
     * client address, and which cannot say anything about headers a real client sends.
     */
    private Received send(String path, Consumer<java.util.Map<String, String>> extra) {
        stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE).withBody("{}")));
        String token = jwtService.generateToken("user-7", List.of("ROLE_USER"));
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        extra.accept(headers);
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://localhost:" + port + path + "?page=2&size=5"));
        headers.forEach(request::header);
        int status;
        try {
            status = java.net.http.HttpClient.newHttpClient().send(request.GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
        List<LoggedRequest> seen = findAll(getRequestedFor(urlPathEqualTo(path)));
        if (seen.isEmpty()) {
            return new Received(status, HttpHeaders.noHeaders(), null);
        }
        LoggedRequest last = seen.get(seen.size() - 1);
        return new Received(status, last.getHeaders(), last.getUrl());
    }

    private static String values(HttpHeaders headers, String name) {
        return headers.getHeader(name).isPresent() ? String.join("|", headers.getHeader(name).values()) : "";
    }

    private static final String LOOPBACK = "(127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|::1)";

    @Test
    @DisplayName("The service gets the client's address in X-Client-Ip, the path and query, and talks under its own Host")
    void serviceLearnsTheClientAddress() {
        Received seen = send("/api/v1/products/501", h -> h.put("User-Agent", "lab-client/1.0"));

        System.out.println("downstream saw: X-Client-Ip=" + values(seen.headers(), "X-Client-Ip") + " Host="
                + values(seen.headers(), "Host") + " url=" + seen.url());
        assertThat(seen.status()).isEqualTo(200);
        assertThat(values(seen.headers(), "X-Client-Ip")).matches(LOOPBACK);
        assertThat(values(seen.headers(), "Host")).startsWith("localhost:");
        assertThat(seen.url()).isEqualTo("/api/v1/products/501?page=2&size=5");
    }

    @Test
    @DisplayName("A client cannot choose its address: X-Client-Ip is overwritten and the Forwarded and X-Forwarded-* headers are dropped")
    void forgedAddressHeadersDoNotReachTheService() {
        Received seen = send("/api/v1/products/502", h -> {
            h.put("X-Client-Ip", "6.6.6.6");
            h.put("X-Forwarded-For", "6.6.6.6");
            h.put("X-Forwarded-Host", "evil.example.test");
            h.put("X-Forwarded-Proto", "https");
            h.put("Forwarded", "for=6.6.6.6;host=evil.example.test;proto=https");
        });

        System.out.println("with forged headers downstream saw: X-Client-Ip=" + values(seen.headers(), "X-Client-Ip")
                + " X-Forwarded-For=" + values(seen.headers(), "X-Forwarded-For") + " X-Forwarded-Host="
                + values(seen.headers(), "X-Forwarded-Host") + " X-Forwarded-Proto=" + values(seen.headers(), "X-Forwarded-Proto")
                + " Forwarded=" + values(seen.headers(), "Forwarded"));
        assertThat(seen.status()).isEqualTo(200);
        assertThat(values(seen.headers(), "X-Client-Ip")).matches(LOOPBACK);
        for (String forged : new String[] {"X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "Forwarded"}) {
            assertThat(seen.headers().getHeader(forged).isPresent()).as(forged + " reaches the service").isFalse();
        }
    }

    @Test
    @DisplayName("A client cannot send its own identity headers: X-User-Id and X-User-Roles come from the verified token")
    void identityHeadersCannotBeSpoofed() {
        Received seen = send("/api/v1/products/503", h -> {
            h.put("X-User-Id", "root");
            h.put("X-User-Roles", "ROLE_ADMIN");
        });

        assertThat(values(seen.headers(), "X-User-Id")).isEqualTo("user-7");
        assertThat(values(seen.headers(), "X-User-Roles")).isEqualTo("ROLE_USER");
    }

    @Test
    @DisplayName("Hop-by-hop headers are not passed on to the service")
    void hopByHopHeadersStayAtTheProxy() {
        int reached = 0;
        for (String[] header : new String[][] {{"Proxy-Authorization", "Basic Zm9vOmJhcg=="}, {"Keep-Alive", "timeout=5"},
                {"TE", "trailers"}, {"Upgrade", "h2c"}}) {
            String path = "/api/v1/products/5" + header[0].length();
            Received seen = send(path, h -> h.put(header[0], header[1]));
            System.out.println("hop-by-hop " + header[0] + ": gateway answered " + seen.status() + ", service saw it: "
                    + seen.headers().getHeader(header[0]).isPresent());
            if (seen.status() == 200) {
                reached++;
                assertThat(seen.headers().getHeader(header[0]).isPresent()).as(header[0] + " reaches the service").isFalse();
            }
        }
        assertThat(reached).as("headers that got a request through to the service").isGreaterThanOrEqualTo(2);
    }
}
