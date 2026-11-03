package dev.stockflow.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;
import dev.stockflow.catalog.AbstractCatalogIntegrationTest;
import dev.stockflow.catalog.CatalogServiceApplication;
import dev.stockflow.catalog.api.ProductService;
import dev.stockflow.catalog.api.dto.ProductUpdateRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

/**
 * Distributed caching: two catalog instances (two application contexts in this JVM, each with its own pool,
 * cache beans, single-flight map and meters) share one PostgreSQL and one Redis. Instance A is the test's own
 * context; instance B is started here against the same database and Redis.
 */
@SpringBootTest(properties = {
        "stockflow.cache.enabled=true",
        "stockflow.cache.product-ttl=30s"
})
@AutoConfigureMockMvc
class ProductCacheTwoInstancesTest extends AbstractCatalogIntegrationTest {

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.12-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    private static ConfigurableApplicationContext instanceB;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MeterRegistry metersA;

    @Autowired
    private ProductService serviceA;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    private ProductService serviceB;
    private MeterRegistry metersB;

    @BeforeEach
    void startInstanceB() throws Exception {
        if (instanceB == null) {
            HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
            LettuceConnectionFactory lettuce = (LettuceConnectionFactory) redisConnectionFactory;
            // command-line arguments, not .properties(): those are defaults that the test application.yml overrides
            instanceB = new SpringApplicationBuilder(CatalogServiceApplication.class).run(
                    "--server.port=0",
                    "--spring.datasource.url=" + pool.getJdbcUrl(),
                    "--spring.datasource.username=" + pool.getUsername(),
                    "--spring.datasource.password=" + pool.getPassword(),
                    "--spring.data.redis.host=" + lettuce.getHostName(),
                    "--spring.data.redis.port=" + lettuce.getPort(),
                    "--stockflow.cache.enabled=true",
                    "--stockflow.cache.product-ttl=30s");
        }
        serviceB = instanceB.getBean(ProductService.class);
        metersB = instanceB.getBean(MeterRegistry.class);
    }

    @AfterAll
    static void stopInstanceB() {
        if (instanceB != null) {
            instanceB.close();
            instanceB = null;
        }
    }

    private static double count(MeterRegistry meters, String result) {
        return meters.counter("catalog.cache", "cache", "product", "result", result).count();
    }

    private static String key(long id) {
        return "catalog:product:v1:" + id;
    }

    private long createProduct(String name) throws Exception {
        String location = mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"TWO-" + System.nanoTime() + "\",\"name\":\"" + name
                                + "\",\"categoryId\":1,\"unitId\":1}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    @Test
    @DisplayName("An entry loaded by instance A is a hit for instance B, with no database load on B")
    void instanceBHitsWhatInstanceACached() throws Exception {
        long id = createProduct("shared");
        double loadsA = count(metersA, "load");
        double loadsB = count(metersB, "load");
        double hitsB = count(metersB, "hit");

        serviceA.get(id);
        assertThat(count(metersA, "load")).isEqualTo(loadsA + 1);

        assertThat(serviceB.get(id).name()).isEqualTo("shared");
        assertThat(count(metersB, "hit")).isEqualTo(hitsB + 1);
        assertThat(count(metersB, "load")).isEqualTo(loadsB);
    }

    @Test
    @DisplayName("An update on instance A evicts the shared entry, so instance B reads the new value")
    void updateOnAEvictsForB() throws Exception {
        long id = createProduct("before");
        serviceA.get(id);
        assertThat(serviceB.get(id).name()).isEqualTo("before");
        assertThat(redis.hasKey(key(id))).isTrue();

        serviceA.update(id, new ProductUpdateRequest("after", null, null, null, null));

        assertThat(redis.hasKey(key(id))).isFalse();
        double loadsB = count(metersB, "load");
        assertThat(serviceB.get(id).name()).isEqualTo("after");
        assertThat(count(metersB, "load")).isEqualTo(loadsB + 1);
    }

    @Test
    @DisplayName("A herd split over two instances costs at most one database load per instance")
    void herdAcrossTwoInstancesLoadsOncePerInstance() throws Exception {
        long id = createProduct("herd");
        redis.delete(key(id));
        double loadsA = count(metersA, "load");
        double loadsB = count(metersB, "load");

        List<Callable<ConcurrentGets.Reply>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(() -> reply(serviceA, id));
            calls.add(() -> reply(serviceB, id));
        }
        List<ConcurrentGets.Reply> replies = ConcurrentGets.runAll(calls);

        double a = count(metersA, "load") - loadsA;
        double b = count(metersB, "load") - loadsB;
        System.out.println("two-instance herd: 40 requests, loads on A = " + a + ", loads on B = " + b);
        assertThat(replies).hasSize(40).allSatisfy(r -> assertThat(r.status()).isEqualTo(200));
        assertThat(a).isBetween(0.0, 1.0);
        assertThat(b).isBetween(0.0, 1.0);
        assertThat(a + b).isBetween(1.0, 2.0);
    }

    private static ConcurrentGets.Reply reply(ProductService service, long id) {
        return new ConcurrentGets.Reply(200, service.get(id).name());
    }
}
