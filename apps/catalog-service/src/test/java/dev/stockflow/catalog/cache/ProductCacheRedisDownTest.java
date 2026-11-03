package dev.stockflow.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.stockflow.catalog.AbstractCatalogIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Redis failure behaviour: the cache is an optimisation, the database is the source of truth. With nothing
 * listening on the Redis port, reads are served from the database, writes still commit, and the failures are
 * counted instead of surfacing as 500s.
 */
@SpringBootTest(properties = {
        "stockflow.cache.enabled=true",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=1",
        "spring.data.redis.timeout=300ms",
        "spring.data.redis.connect-timeout=300ms"
})
@AutoConfigureMockMvc
class ProductCacheRedisDownTest extends AbstractCatalogIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meters;

    @Test
    @DisplayName("Reads and writes keep working when Redis is unreachable")
    void requestsSucceedWithoutRedis() throws Exception {
        double errors = meters.counter("catalog.cache", "cache", "product", "result", "error").count();

        long start = System.nanoTime();
        mockMvc.perform(get("/api/v1/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
        long readMillis = (System.nanoTime() - start) / 1_000_000;
        // one failed read, and no second timeout spent on writing a copy to a Redis that just failed
        assertThat(meters.counter("catalog.cache", "cache", "product", "result", "error").count())
                .isEqualTo(errors + 1);

        mockMvc.perform(patch("/api/v1/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"updated while redis was down\"}"))
                .andExpect(status().isOk());

        assertThat(meters.counter("catalog.cache", "cache", "product", "result", "error").count())
                .isGreaterThan(errors);
        // bounded by the configured timeouts, not by Lettuce's reconnect queue
        assertThat(readMillis).isLessThan(3_000);
    }
}
