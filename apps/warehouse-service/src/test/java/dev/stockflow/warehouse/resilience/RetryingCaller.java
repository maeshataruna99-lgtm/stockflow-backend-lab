package dev.stockflow.warehouse.resilience;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Calls an action and repeats it while the failure is one the caller has declared retryable, at most
 * policy.maxAttempts() times in total, waiting the policy's delay between attempts. Anything else is thrown at once.
 * The wait goes through a callback so tests can record it instead of sleeping.
 */
public final class RetryingCaller {

    private final BackoffPolicy policy;
    private final Predicate<Throwable> retryable;
    private final Consumer<Duration> sleeper;

    public RetryingCaller(BackoffPolicy policy, Predicate<Throwable> retryable, Consumer<Duration> sleeper) {
        this.policy = policy;
        this.retryable = retryable;
        this.sleeper = sleeper;
    }

    public <T> T call(Callable<T> action) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                if (attempt >= policy.maxAttempts() || !retryable.test(e)) {
                    throw e;
                }
                sleeper.accept(policy.delayBeforeRetry(attempt));
            }
        }
    }
}
