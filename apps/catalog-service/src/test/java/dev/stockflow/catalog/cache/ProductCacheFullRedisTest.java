package dev.stockflow.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.stockflow.catalog.AbstractCatalogIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

/**
 * A Redis that is full and refuses writes (noeviction) is another way for the cache to fail. Reads of products
 * must still return the right answer from the database, the refused write is counted as an error, and the cache
 * works again once there is room.
 */
@SpringBootTest(properties = "stockflow.cache.enabled=true")
@AutoConfigureMockMvc
class ProductCacheFullRedisTest extends AbstractCatalogIntegrationTest {

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.12-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--maxmemory", "2mb", "--maxmemory-policy", "noeviction",
                    "--appendonly", "no");

    static {
        REDIS.start();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MeterRegistry meters;

    private double errors() {
        return meters.counter("catalog.cache", "cache", "product", "result", "error").count();
    }

    private void fillRedisUntilItRefusesWrites() {
        String filler = "x".repeat(1024);
        for (int i = 0; i < 10_000; i++) {
            try {
                redis.opsForValue().set("filler:" + i, filler);
            } catch (DataAccessException e) {
                assertThat(NestedExceptionUtils.getMostSpecificCause(e).getMessage()).startsWith("OOM");
                return;
            }
        }
        throw new AssertionError("Redis never refused a write");
    }

    @Test
    @DisplayName("With Redis full, reads still succeed, the refused write is counted, and caching resumes with room")
    void readsSurviveAFullRedis() throws Exception {
        String created = mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"FULL-" + System.nanoTime() + "\",\"name\":\"full redis\","
                                + "\"categoryId\":1,\"unitId\":1}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        String id = created.substring(created.lastIndexOf('/') + 1);
        String key = "catalog:product:v1:" + id;

        fillRedisUntilItRefusesWrites();
        double before = errors();

        mockMvc.perform(get("/api/v1/products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("full redis"));
        assertThat(errors()).isEqualTo(before + 1);
        assertThat(redis.hasKey(key)).isFalse();

        List<String> fillers = redis.keys("filler:*").stream().toList();
        redis.delete(fillers);

        mockMvc.perform(get("/api/v1/products/" + id)).andExpect(status().isOk());
        assertThat(redis.hasKey(key)).isTrue();
        double hits = meters.counter("catalog.cache", "cache", "product", "result", "hit").count();
        mockMvc.perform(get("/api/v1/products/" + id)).andExpect(status().isOk());
        assertThat(meters.counter("catalog.cache", "cache", "product", "result", "hit").count())
                .isEqualTo(hits + 1);
    }
}
