package dev.stockflow.catalog.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled switch for the whole cache; false sends every read to the database
 * @param productTtl how long a cached product may be served; it also bounds staleness when an eviction is lost
 * @param singleFlight when true, simultaneous misses for one product share one database load per instance
 */
@ConfigurationProperties(prefix = "stockflow.cache")
public record CacheProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60s") Duration productTtl,
        @DefaultValue("true") boolean singleFlight) {
}
