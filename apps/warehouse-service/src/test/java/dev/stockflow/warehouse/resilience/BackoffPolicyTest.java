package dev.stockflow.warehouse.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stockflow.warehouse.resilience.BackoffPolicy.Jitter;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Bounds of a retry: how many attempts, which failures, how long the waits are. */
class BackoffPolicyTest {

    private static BackoffPolicy seeded(Jitter jitter, int attempts, long seed) {
        return new BackoffPolicy(Duration.ofMillis(100), Duration.ofMillis(500), attempts, jitter,
                new java.util.SplittableRandom(seed));
    }

    @Test
    @DisplayName("Without jitter the waits double and stop at the cap: 100, 200, 400, 500, 500")
    void exponentialUpToTheCap() {
        BackoffPolicy none = seeded(Jitter.NONE, 6, 1);

        List<Long> waits = new ArrayList<>();
        for (int retry = 1; retry <= 5; retry++) {
            waits.add(none.delayBeforeRetry(retry).toMillis());
        }
        assertThat(waits).containsExactly(100L, 200L, 400L, 500L, 500L);
    }

    @Test
    @DisplayName("Full jitter stays inside [0, raw] and averages about half of it; equal jitter stays inside [raw/2, raw]")
    void jitterStaysInsideItsRange() {
        BackoffPolicy full = seeded(Jitter.FULL, 6, 42);
        BackoffPolicy equal = seeded(Jitter.EQUAL, 6, 42);

        double fullSum = 0;
        for (int i = 0; i < 10_000; i++) {
            long f = full.delayBeforeRetry(3).toMillis(); // raw 400
            long e = equal.delayBeforeRetry(3).toMillis();
            assertThat(f).isBetween(0L, 400L);
            assertThat(e).isBetween(200L, 400L);
            fullSum += f;
        }
        double mean = fullSum / 10_000;
        System.out.println("full jitter, raw 400 ms: mean over 10,000 draws = " + mean + " ms");
        assertThat(mean).isBetween(185.0, 215.0);
    }

    @Test
    @DisplayName("The number of attempts is bounded: an action that always fails is called maxAttempts times")
    void attemptsAreBounded() {
        List<Duration> slept = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        RetryingCaller caller = new RetryingCaller(seeded(Jitter.FULL, 4, 7), e -> e instanceof IOException, slept::add);

        assertThatThrownBy(() -> caller.call(() -> {
            calls.incrementAndGet();
            throw new IOException("down");
        })).isInstanceOf(IOException.class);

        assertThat(calls.get()).isEqualTo(4);
        assertThat(slept).hasSize(3);
    }

    @Test
    @DisplayName("Only the declared failure classes are retried; anything else is thrown at once")
    void onlyEligibleFailuresAreRetried() {
        AtomicInteger calls = new AtomicInteger();
        RetryingCaller caller = new RetryingCaller(seeded(Jitter.FULL, 5, 7), e -> e instanceof IOException, d -> { });

        assertThatThrownBy(() -> caller.call(() -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("a 400 is not worth repeating");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("A call that succeeds on the third attempt returns its result after two waits")
    void succeedsAfterTransientFailures() throws Exception {
        List<Duration> slept = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        RetryingCaller caller = new RetryingCaller(seeded(Jitter.EQUAL, 5, 3), e -> e instanceof IOException, slept::add);

        String result = caller.call(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new IOException("transient");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(slept).hasSize(2);
        assertThat(slept.get(0).toMillis()).isBetween(50L, 100L);
        assertThat(slept.get(1).toMillis()).isBetween(100L, 200L);
    }
}
