package dev.stockflow.warehouse.resilience;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with optional jitter, for the retry lab (unit 8.2). The raw wait before retry n is
 * min(cap, base * 2^(n-1)). Jitter then spreads it: FULL picks a uniform value in [0, raw], EQUAL keeps half of it and
 * randomises the other half, NONE keeps raw. The random source is injected so a test can fix the seed.
 */
public final class BackoffPolicy {

    public enum Jitter { NONE, FULL, EQUAL }

    private final Duration base;
    private final Duration cap;
    private final int maxAttempts;
    private final Jitter jitter;
    private final RandomGenerator random;

    public BackoffPolicy(Duration base, Duration cap, int maxAttempts, Jitter jitter, RandomGenerator random) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("at least one attempt");
        }
        this.base = base;
        this.cap = cap;
        this.maxAttempts = maxAttempts;
        this.jitter = jitter;
        this.random = random;
    }

    /** Total attempts, the first one included: the retries are maxAttempts - 1. */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** The wait before the given retry (1 = the first retry). */
    public Duration delayBeforeRetry(int retry) {
        long raw = Math.min(cap.toMillis(), base.toMillis() * (1L << Math.min(retry - 1, 30)));
        return Duration.ofMillis(switch (jitter) {
            case NONE -> raw;
            case FULL -> (long) (random.nextDouble() * raw);
            case EQUAL -> raw / 2 + (long) (random.nextDouble() * (raw - raw / 2));
        });
    }
}
