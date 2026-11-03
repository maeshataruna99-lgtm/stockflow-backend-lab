package dev.stockflow.catalog.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.stockflow.catalog.api.dto.ProductResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cache-aside for one product by id. Redis only ever holds a copy: any Redis or JSON failure is counted and
 * treated as a miss (read) or a lost eviction (write), never as a failed request. A lost eviction leaves the old
 * copy in place until its TTL runs out, which is the staleness bound.
 */
@Component
@EnableConfigurationProperties(CacheProperties.class)
public class ProductCache {

    private static final Logger log = LoggerFactory.getLogger(ProductCache.class);
    private static final String KEY_PREFIX = "catalog:product:v1:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final CacheProperties properties;
    private final MeterRegistry meters;

    ProductCache(StringRedisTemplate redis, ObjectMapper objectMapper, CacheProperties properties,
                 MeterRegistry meters) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.meters = meters;
    }

    /**
     * Returns the cached product, or loads it and caches it. When Redis itself fails the load result is not
     * written back: that would only spend a second timeout on a Redis that just failed.
     */
    public ProductResponse getOrLoad(long id, Supplier<ProductResponse> loader) {
        if (!properties.enabled()) {
            return loader.get();
        }
        boolean redisReachable = true;
        try {
            String json = redis.opsForValue().get(key(id));
            if (json != null) {
                ProductResponse cached = objectMapper.readValue(json, ProductResponse.class);
                count("hit");
                return cached;
            }
            count("miss");
        } catch (DataAccessException e) {
            redisReachable = false;
            count("error");
            log.warn("Product cache read failed for id {}, using the database: {}", id, e.toString());
        } catch (JsonProcessingException e) {
            count("error");
            log.warn("Product cache entry for id {} is unreadable and will be replaced: {}", id, e.toString());
        }

        ProductResponse loaded = loader.get();
        if (redisReachable) {
            store(id, loaded);
        }
        return loaded;
    }

    /** Evicts once the surrounding transaction has committed, so a rollback never drops a valid entry. */
    public void evictAfterCommit(long id) {
        if (!properties.enabled()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(id);
                }
            });
        } else {
            evict(id);
        }
    }

    private void store(long id, ProductResponse product) {
        try {
            redis.opsForValue().set(key(id), objectMapper.writeValueAsString(product), properties.productTtl());
        } catch (DataAccessException | JsonProcessingException e) {
            count("error");
            log.warn("Product cache write failed for id {}: {}", id, e.toString());
        }
    }

    private void evict(long id) {
        try {
            redis.delete(key(id));
        } catch (DataAccessException e) {
            count("error");
            log.warn("Product cache eviction failed for id {}; the old copy lives until its TTL ({}): {}",
                    id, properties.productTtl(), e.toString());
        }
    }

    private void count(String result) {
        meters.counter("catalog.cache", "cache", "product", "result", result).increment();
    }

    private static String key(long id) {
        return KEY_PREFIX + id;
    }
}
