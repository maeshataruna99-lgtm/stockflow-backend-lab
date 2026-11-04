package dev.stockflow.warehouse.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.stockflow.warehouse.AbstractWarehouseIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Liveness answers "is the process alive and not wedged" and must not depend on the database; readiness answers "may
 * traffic be sent here" and must. Requests carry no token: a load balancer or an orchestrator has none. The database
 * is frozen with a Docker pause, which makes connections hang the way a stuck database does; the Hikari waits are
 * shortened here (src/test/resources/application.yml shadows the main one).
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.connection-timeout=1500",
        "spring.datasource.hikari.validation-timeout=1000"
})
class HealthProbesTest extends AbstractWarehouseIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private HealthEndpointGroups groups;

    private MockMvc withoutToken() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("Liveness and readiness are separate groups: readiness includes the database, liveness does not")
    void groupsAreDistinct() {
        assertThat(groups.get("liveness").isMember("livenessState")).isTrue();
        assertThat(groups.get("liveness").isMember("db")).isFalse();
        assertThat(groups.get("readiness").isMember("readinessState")).isTrue();
        assertThat(groups.get("readiness").isMember("db")).isTrue();
    }

    @Test
    @DisplayName("Both probes answer 200 UP without a token while the database is up")
    void probesAreUpAndOpen() throws Exception {
        withoutToken().perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        withoutToken().perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("With the database frozen readiness is 503 DOWN while liveness stays 200 UP; readiness recovers after")
    void readinessFollowsTheDatabaseAndLivenessDoesNot() throws Exception {
        pauseDatabase();
        try {
            long started = System.nanoTime();
            withoutToken().perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("DOWN"));
            long millis = (System.nanoTime() - started) / 1_000_000;
            System.out.println("readiness answered 503 after " + millis + " ms with the database frozen");
            withoutToken().perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
            assertThat(millis).as("the check is bounded, it does not hang").isLessThan(15_000);
        } finally {
            resumeDatabase();
        }
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                withoutToken().perform(get("/actuator/health/readiness")).andExpect(status().isOk()));
    }
}
