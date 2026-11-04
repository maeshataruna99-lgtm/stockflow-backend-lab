package dev.stockflow.warehouse.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The database goes away and the probes must say different things: readiness 503 (no traffic), liveness 200 (a restart
 * would not help). The database is a container of this class alone, stopped in the test, so the connection is refused and
 * the check fails the same way on every platform. An earlier version froze the shared container with docker pause: it
 * passed on Windows in 2.5 s and hung for 43 minutes on the Linux CI runner (connections to a frozen server wait for an
 * answer that never comes), and was removed. The frozen database is shown by the live lab labs/resilience/probes-lab.sh.
 * The Hikari waits are shortened here (src/test/resources/application.yml shadows the main one).
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.connection-timeout=1500",
        "spring.datasource.hikari.validation-timeout=1000"
})
class DatabaseOutageHealthTest {

    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16.15-alpine");

    static {
        DATABASE.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired
    private WebApplicationContext context;

    @Test
    @DisplayName("With the database gone readiness is 503 DOWN, quickly, while liveness stays 200 UP")
    void readinessFollowsTheDatabaseAndLivenessDoesNot() throws Exception {
        MockMvc noToken = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        noToken.perform(get("/actuator/health/readiness")).andExpect(status().isOk());

        DATABASE.stop();

        long started = System.nanoTime();
        noToken.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.println("readiness answered 503 after " + millis + " ms with the database stopped");
        noToken.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        assertThat(millis).as("the check is bounded").isLessThan(10_000);
    }
}
