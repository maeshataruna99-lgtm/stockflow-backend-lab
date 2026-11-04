package dev.stockflow.gateway.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.stockflow.gateway.security.JwtService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Sends GETs to a downstream that always answers 503 and reads the gaps between the gateway's attempts from WireMock. */
final class RetryJitterTestSupport {

    private RetryJitterTestSupport() {
    }

    /** Gaps in ms: index 0 = wait before the first retry, index 1 = wait before the second retry. */
    record Gaps(List<Long> first, List<Long> second) {
    }

    static Gaps collect(WebTestClient client, JwtService jwt, int requests) {
        String token = jwt.generateToken("user-1", List.of("ROLE_USER"));
        // one throw-away request first: the first retry of a cold JVM waits noticeably longer (measured 419 ms and 438 ms)
        String warmUp = "/api/v1/products/999";
        stubFor(get(urlEqualTo(warmUp)).willReturn(aResponse().withStatus(503)));
        client.get().uri(warmUp).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
        List<Long> first = new ArrayList<>();
        List<Long> second = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            String url = "/api/v1/products/" + (1000 + i);
            stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(503)));
            client.get().uri(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                    .expectStatus().isEqualTo(503);
            List<LoggedRequest> attempts = new ArrayList<>(findAll(getRequestedFor(urlEqualTo(url))));
            attempts.sort((a, b) -> a.getLoggedDate().compareTo(b.getLoggedDate()));
            if (attempts.size() != 3) {
                throw new AssertionError("expected 3 attempts for " + url + " but WireMock saw " + attempts.size());
            }
            first.add(attempts.get(1).getLoggedDate().getTime() - attempts.get(0).getLoggedDate().getTime());
            second.add(attempts.get(2).getLoggedDate().getTime() - attempts.get(1).getLoggedDate().getTime());
        }
        return new Gaps(first, second);
    }

    static long spread(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).max().orElse(0) - values.stream().mapToLong(Long::longValue).min().orElse(0);
    }
}
