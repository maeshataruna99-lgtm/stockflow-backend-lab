package dev.stockflow.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

/**
 * Cache stampede: many requests for one product arrive while its cache entry is missing. With single-flight on,
 * one of them loads the product from the database and the others wait for that result; the unprotected variant
 * (ProductCacheStampedeUnprotectedTest) shows what happens without it.
 */
@SpringBootTest(properties = {
        "stockflow.cache.enabled=true",
        "stockflow.cache.single-flight=true"
})
@AutoConfigureMockMvc
class ProductCacheStampedeTest extends AbstractCatalogIntegrationTest {

    static final int REQUESTS = 40;

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.12-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MeterRegistry meters;

    private double count(String result) {
        return meters.counter("catalog.cache", "cache", "product", "result", result).count();
    }

    private long createProduct() throws Exception {
        String location = mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"STAMPEDE-" + System.nanoTime() + "\",\"name\":\"stampede\","
                                + "\"categoryId\":1,\"unitId\":1}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    @Test
    @DisplayName("40 simultaneous reads of an uncached product cause exactly one database load")
    void oneLoadForManySimultaneousMisses() throws Exception {
        long id = createProduct();
        redis.delete("catalog:product:v1:" + id);
        double loads = count("load");

        List<ConcurrentGets.Reply> replies = ConcurrentGets.run(mockMvc, "/api/v1/products/" + id, REQUESTS);

        assertThat(replies).hasSize(REQUESTS).allSatisfy(r -> assertThat(r.status()).isEqualTo(200));
        assertThat(replies.stream().map(ConcurrentGets.Reply::body).distinct()).hasSize(1);
        assertThat(count("load") - loads).isEqualTo(1);
        assertThat(redis.hasKey("catalog:product:v1:" + id)).isTrue();
    }

    @Test
    @DisplayName("Simultaneous reads of a missing product all get 404 and nothing is cached")
    void waitersGetTheLeadersNotFound() throws Exception {
        List<ConcurrentGets.Reply> replies = ConcurrentGets.run(mockMvc, "/api/v1/products/987001", 20);

        assertThat(replies).allSatisfy(r -> assertThat(r.status()).isEqualTo(404));
        assertThat(redis.hasKey("catalog:product:v1:987001")).isFalse();
    }
}
