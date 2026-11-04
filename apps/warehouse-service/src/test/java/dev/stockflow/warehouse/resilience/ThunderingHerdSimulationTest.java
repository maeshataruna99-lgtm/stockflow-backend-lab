package dev.stockflow.warehouse.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stockflow.warehouse.resilience.BackoffPolicy.Jitter;
import java.time.Duration;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Why jitter exists, measured on a simulated clock (no sleeping, fixed seed). 1,000 clients all see their request fail
 * at time 0 and retry with exponential backoff, up to 5 attempts. The load the server sees is the number of retry
 * requests that arrive in each time bucket. Without jitter every client retries at the same instants, so the server gets
 * all 1,000 at once, each time: the spike that keeps a recovering server down. Jitter spreads the clients only over the
 * width of the backoff window, so how much it helps depends on that width compared with the bucket.
 */
class ThunderingHerdSimulationTest {

    private static final int CLIENTS = 1000;

    private static int peakRetriesPerBucket(Jitter jitter, long baseMillis, long capMillis, long bucketMillis) {
        SplittableRandom random = new SplittableRandom(1);
        TreeMap<Long, Integer> arrivals = new TreeMap<>();
        for (int client = 0; client < CLIENTS; client++) {
            BackoffPolicy policy = new BackoffPolicy(Duration.ofMillis(baseMillis), Duration.ofMillis(capMillis), 5, jitter, random);
            long clock = 0;
            for (int retry = 1; retry < policy.maxAttempts(); retry++) {
                clock += policy.delayBeforeRetry(retry).toMillis();
                arrivals.merge(clock / bucketMillis, 1, Integer::sum);
            }
        }
        return arrivals.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    @Test
    @DisplayName("Backoff of 1 s: without jitter 1,000 clients hit the server together; full jitter cuts the peak to a fraction")
    void jitterFlattensTheRetryPeak() {
        int none = peakRetriesPerBucket(Jitter.NONE, 1000, 30_000, 100);
        int equal = peakRetriesPerBucket(Jitter.EQUAL, 1000, 30_000, 100);
        int full = peakRetriesPerBucket(Jitter.FULL, 1000, 30_000, 100);

        System.out.println("base 1 s, 100 ms buckets, 1,000 clients, peak retries in one bucket: no jitter " + none
                + ", equal jitter " + equal + ", full jitter " + full);
        assertThat(none).isEqualTo(CLIENTS);
        assertThat(equal).isLessThan(none / 2);
        assertThat(full).isLessThan(none / 4);
    }

    @Test
    @DisplayName("Backoff of 100 ms with 50 ms buckets: the window is only two buckets wide, so the best case is about half")
    void aNarrowWindowCannotBeFlattenedMuch() {
        int none = peakRetriesPerBucket(Jitter.NONE, 100, 1000, 50);
        int equal = peakRetriesPerBucket(Jitter.EQUAL, 100, 1000, 50);
        int full = peakRetriesPerBucket(Jitter.FULL, 100, 1000, 50);

        System.out.println("base 100 ms, 50 ms buckets, 1,000 clients, peak retries in one bucket: no jitter " + none
                + ", equal jitter " + equal + ", full jitter " + full);
        assertThat(none).isEqualTo(CLIENTS);
        assertThat(full).isLessThan(none);
        assertThat(full).isGreaterThan(CLIENTS / 3);
    }
}
