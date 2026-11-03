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

/** The stampede itself: single-flight off, the same 40 simultaneous reads, and several database loads. */
@SpringBootTest(properties = {
        "stockflow.cache.enabled=true",
        "stockflow.cache.single-flight=false"
})
@AutoConfigureMockMvc
class ProductCacheStampedeUnprotectedTest extends AbstractCatalogIntegrationTest {

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

    @Test
    @DisplayName("Without single-flight, simultaneous misses each load the product from the database")
    void manyLoadsForManySimultaneousMisses() throws Exception {
        String location = mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"STAMPEDE-OFF-" + System.nanoTime() + "\",\"name\":\"stampede\","
                                + "\"categoryId\":1,\"unitId\":1}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        long id = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
        redis.delete("catalog:product:v1:" + id);
        double loads = meters.counter("catalog.cache", "cache", "product", "result", "load").count();

        List<ConcurrentGets.Reply> replies =
                ConcurrentGets.run(mockMvc, "/api/v1/products/" + id, ProductCacheStampedeTest.REQUESTS);

        assertThat(replies).allSatisfy(r -> assertThat(r.status()).isEqualTo(200));
        double extra = meters.counter("catalog.cache", "cache", "product", "result", "load").count() - loads;
        System.out.println("STAMPEDE unprotected: " + extra + " database loads for "
                + ProductCacheStampedeTest.REQUESTS + " simultaneous requests");
        assertThat(extra).isGreaterThanOrEqualTo(3);
    }
}
