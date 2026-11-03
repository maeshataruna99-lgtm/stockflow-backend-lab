package dev.stockflow.catalog.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.stockflow.catalog.api.dto.ProductResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p>Counter {@code catalog.cache} results: hit (served from Redis), miss (not in Redis), load (the database was
 * read), coalesced (a miss that was answered by another request's load), error (a Redis or JSON failure).
 */
@Component
@EnableConfigurationProperties(CacheProperties.class)
public class ProductCache {

    private static final Logger log = LoggerFactory.getLogger(ProductCache.class);
    private static final String KEY_PREFIX = "catalog:product:v1:";

    /** What one look into Redis found: the product if cached, and whether Redis answered at all. */
    private record Lookup(ProductResponse product, boolean redisReachable) {
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final CacheProperties properties;
    private final MeterRegistry meters;
    // shortcut: single-flight is per instance, so N instances can still load once each; upgrade to a Redis lock if that matters
    private final ConcurrentHashMap<Long, CompletableFuture<ProductResponse>> inFlight = new ConcurrentHashMap<>();

    ProductCache(StringRedisTemplate redis, ObjectMapper objectMapper, CacheProperties properties,
                 MeterRegistry meters) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.meters = meters;
    }

    /**
     * Returns the cached product, or loads it and caches it. When Redis itself fails the load result is not
     * written back: that would only spend a second timeout on a Redis that just failed. With single-flight on,
     * simultaneous misses for one id wait for a single load instead of each hitting the database.
     */
    public ProductResponse getOrLoad(long id, Supplier<ProductResponse> loader) {
        if (!properties.enabled()) {
            return loader.get();
        }
        Lookup first = lookup(id, true);
        if (first.product() != null) {
            return first.product();
        }
        if (!properties.singleFlight()) {
            return load(id, loader, first.redisReachable());
        }

        CompletableFuture<ProductResponse> mine = new CompletableFuture<>();
        CompletableFuture<ProductResponse> leader = inFlight.putIfAbsent(id, mine);
        if (leader != null) {
            count("coalesced");
            return await(leader);
        }
        try {
            // the previous leader may have stored the product between our first look and becoming leader
            Lookup again = first.redisReachable() ? lookup(id, false) : first;
            ProductResponse result;
            if (again.product() != null) {
                count("coalesced");
                result = again.product();
            } else {
                result = load(id, loader, again.redisReachable());
            }
            mine.complete(result);
            return result;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(id, mine);
        }
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

    private Lookup lookup(long id, boolean countResult) {
        try {
            String json = redis.opsForValue().get(key(id));
            if (json == null) {
                if (countResult) {
                    count("miss");
                }
                return new Lookup(null, true);
            }
            ProductResponse cached = objectMapper.readValue(json, ProductResponse.class);
            if (countResult) {
                count("hit");
            }
            return new Lookup(cached, true);
        } catch (DataAccessException e) {
            count("error");
            log.warn("Product cache read failed for id {}, using the database: {}", id, e.toString());
            return new Lookup(null, false);
        } catch (JsonProcessingException e) {
            count("error");
            log.warn("Product cache entry for id {} is unreadable and will be replaced: {}", id, e.toString());
            return new Lookup(null, true);
        }
    }

    private ProductResponse load(long id, Supplier<ProductResponse> loader, boolean redisReachable) {
        count("load");
        ProductResponse loaded = loader.get();
        if (redisReachable) {
            store(id, loaded);
        }
        return loaded;
    }

    private static ProductResponse await(CompletableFuture<ProductResponse> leader) {
        try {
            return leader.join();
        } catch (CompletionException e) {
            // the leader's own failure (for example not found) is the answer for every waiter
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
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
