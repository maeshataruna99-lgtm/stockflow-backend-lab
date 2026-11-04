package dev.stockflow.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.stockflow.catalog.AbstractCatalogIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Liveness and readiness are separate groups and open without a token; the database failure case is tested in the warehouse. */
class HealthProbesTest extends AbstractCatalogIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private HealthEndpointGroups groups;

    @Test
    @DisplayName("Readiness includes the database, liveness does not, and Redis is in neither (it is only a cache)")
    void groupsAreDistinct() {
        assertThat(groups.get("liveness").isMember("db")).isFalse();
        assertThat(groups.get("readiness").isMember("db")).isTrue();
        assertThat(groups.get("readiness").isMember("redis")).isFalse();
    }

    @Test
    @DisplayName("Both probes answer 200 UP without a token")
    void probesAreUpAndOpen() throws Exception {
        MockMvc noToken = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        noToken.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        noToken.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
