package dev.stockflow.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.stockflow.catalog.AbstractCatalogIntegrationTest;
import dev.stockflow.catalog.api.ProductService;
import dev.stockflow.catalog.api.dto.ProductUpdateRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * Cache-aside on GET /api/v1/products/{id} against a real Redis (image pinned per ADR 0001) and the real
 * PostgreSQL container: miss then hit, invalidation after commit, no stale value after a PATCH, nothing cached
 * for a missing product. Each test creates its own product, so the shared containers do not couple tests.
 */
@SpringBootTest(properties = {
        "stockflow.cache.enabled=true",
        "stockflow.cache.product-ttl=30s"
})
@AutoConfigureMockMvc
class ProductCacheTest extends AbstractCatalogIntegrationTest {

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

    @Autowired
    private ProductService productService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private double count(String result) {
        return meters.counter("catalog.cache", "cache", "product", "result", result).count();
    }

    private static String key(long id) {
        return "catalog:product:v1:" + id;
    }

    private long createProduct(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"CACHE-" + System.nanoTime() + "\",\"name\":\"" + name
                                + "\",\"categoryId\":1,\"unitId\":1}"))
                .andExpect(status().isCreated())
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    @Test
    @DisplayName("The first read is a miss and fills the cache with a TTL; the second read is a hit")
    void missThenHit() throws Exception {
        long id = createProduct("miss then hit");
        double misses = count("miss");
        double hits = count("hit");

        mockMvc.perform(get("/api/v1/products/" + id)).andExpect(status().isOk());
        assertThat(count("miss")).isEqualTo(misses + 1);
        assertThat(redis.hasKey(key(id))).isTrue();
        assertThat(redis.getExpire(key(id))).isBetween(1L, 30L);

        mockMvc.perform(get("/api/v1/products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("miss then hit"));
        assertThat(count("hit")).isEqualTo(hits + 1);
        assertThat(count("miss")).isEqualTo(misses + 1);
    }

    @Test
    @DisplayName("A PATCH evicts the entry after commit, so the next read is fresh from the database")
    void updateEvictsAndNextReadIsFresh() throws Exception {
        long id = createProduct("before");
        mockMvc.perform(get("/api/v1/products/" + id)).andExpect(status().isOk());
        assertThat(redis.hasKey(key(id))).isTrue();

        mockMvc.perform(patch("/api/v1/products/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"after\"}"))
                .andExpect(status().isOk());
        assertThat(redis.hasKey(key(id))).isFalse();

        double misses = count("miss");
        mockMvc.perform(get("/api/v1/products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("after"));
        assertThat(count("miss")).isEqualTo(misses + 1);
    }

    @Test
    @DisplayName("A rolled-back update leaves the entry alone: eviction only runs after a commit")
    void rolledBackUpdateKeepsTheEntry() throws Exception {
        long id = createProduct("stays");
        mockMvc.perform(get("/api/v1/products/" + id)).andExpect(status().isOk());
        assertThat(redis.hasKey(key(id))).isTrue();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try {
            tx.executeWithoutResult(status -> {
                productService.update(id, new ProductUpdateRequest("never committed", null, null, null, null));
                throw new IllegalStateException("force a rollback");
            });
        } catch (IllegalStateException expected) {
            // the rollback is the point of the test
        }

        assertThat(redis.hasKey(key(id))).isTrue();
        mockMvc.perform(get("/api/v1/products/" + id)).andExpect(jsonPath("$.name").value("stays"));
    }

    @Test
    @DisplayName("A missing product is never cached")
    void notFoundIsNotCached() throws Exception {
        mockMvc.perform(get("/api/v1/products/987654")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/products/987654")).andExpect(status().isNotFound());

        assertThat(redis.hasKey(key(987654))).isFalse();
    }
}
