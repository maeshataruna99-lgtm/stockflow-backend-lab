package dev.stockflow.warehouse.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.stockflow.warehouse.AbstractWarehouseIntegrationTest;
import dev.stockflow.warehouse.TestTokens;
import dev.stockflow.warehouse.WarehouseServiceApplication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.FileSystemResource;

/**
 * Graceful shutdown, reproduced: a second instance of the service is started in this JVM against the test database, a
 * request that keeps a connection busy for 3 s is sent to it over real HTTP, and while that request is in flight the
 * application context is closed (the same path a SIGTERM takes). With graceful shutdown the request completes with 200
 * and the shutdown waits for it; with the immediate mode the request is cut off.
 */
class GracefulShutdownTest extends AbstractWarehouseIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private record Outcome(Integer status, String failure, long requestMillis, long closeMillis,
                           boolean newRequestRefused, List<String> availabilityEvents) {
    }

    private Outcome shutDownDuringARequest(String shutdownMode) throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        ApplicationListener<AvailabilityChangeEvent<?>> record = e -> events.add(e.getState().toString());
        ConfigurableApplicationContext instance = new SpringApplicationBuilder(WarehouseServiceApplication.class)
                .listeners(record)
                .run("--server.port=0",
                        "--spring.datasource.url=" + pool.getJdbcUrl(),
                        "--spring.datasource.username=" + pool.getUsername(),
                        "--spring.datasource.password=" + pool.getPassword(),
                        "--stockflow.lab.unsafe-stock=true",
                        "--server.shutdown=" + shutdownMode,
                        "--spring.lifecycle.timeout-per-shutdown-phase=10s");
        int port = ((ServletWebServerApplicationContext) instance).getWebServer().getPort();
        HttpClient client = HttpClient.newHttpClient();
        String token = TestTokens.token("test-admin", List.of("ROLE_ADMIN", "ROLE_USER"));
        HttpRequest hold = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/stock/lab/hold?millis=3000"))
                .header("Authorization", "Bearer " + token).GET().build();

        long sent = System.nanoTime();
        CompletableFuture<HttpResponse<String>> inFlight = client.sendAsync(hold, HttpResponse.BodyHandlers.ofString());
        Thread.sleep(700); // the request is now inside its 3 s database sleep
        long[] closeMillis = new long[1];
        Thread closer = new Thread(() -> {
            long started = System.nanoTime();
            instance.close();
            closeMillis[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        });
        closer.start();
        Thread.sleep(300);

        boolean refused;
        try {
            client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health/liveness"))
                    .timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.ofString());
            refused = false;
        } catch (IOException | InterruptedException e) {
            refused = true;
        }

        Integer status = null;
        String failure = null;
        try {
            status = inFlight.get(20, TimeUnit.SECONDS).statusCode();
        } catch (ExecutionException e) {
            failure = NestedExceptionUtils.getMostSpecificCause(e).getClass().getSimpleName();
        }
        long requestMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent);
        closer.join(30_000);
        Outcome outcome = new Outcome(status, failure, requestMillis, closeMillis[0], refused, List.copyOf(events));
        System.out.println(shutdownMode + ": " + outcome);
        return outcome;
    }

    @Test
    @DisplayName("Graceful: the request in flight when the context closes completes with 200, and the shutdown waited for it")
    void gracefulShutdownLetsTheRequestFinish() throws Exception {
        Outcome outcome = shutDownDuringARequest("graceful");

        assertThat(outcome.status()).isEqualTo(200);
        assertThat(outcome.requestMillis()).isGreaterThanOrEqualTo(2900);
        assertThat(outcome.closeMillis()).as("the close waited for the request").isGreaterThanOrEqualTo(1500);
        assertThat(outcome.newRequestRefused()).as("no new request is served during the drain").isTrue();
        assertThat(outcome.availabilityEvents()).as("readiness flips when the shutdown starts").contains("REFUSING_TRAFFIC");
    }

    @Test
    @DisplayName("Immediate: the same request is cut off when the context closes")
    void immediateShutdownCutsTheRequest() throws Exception {
        Outcome outcome = shutDownDuringARequest("immediate");

        assertThat(outcome.failure()).as("the client sees a broken connection, not a response").isNotNull();
        assertThat(outcome.status()).isNull();
    }

    @Test
    @DisplayName("application.yml asks for graceful shutdown with a timeout")
    void configurationIsGraceful() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("src/main/resources/application.yml")));
        Properties main = yaml.getObject();

        assertThat(main.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(main.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("20s");
    }
}
