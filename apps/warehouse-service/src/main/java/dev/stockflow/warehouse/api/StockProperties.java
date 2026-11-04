package dev.stockflow.warehouse.api;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounds of the stock locking strategies: how long a pessimistic withdrawal waits for the row lock, and how many
 * times an optimistic withdrawal re-reads and retries after losing the version race. Also how long an idempotency
 * key is remembered.
 */
@ConfigurationProperties("stockflow.stock")
public record StockProperties(
        @DefaultValue("2s") Duration lockTimeout,
        @DefaultValue("5") int optimisticAttempts,
        @DefaultValue("24h") Duration idempotencyRetention) {
}
