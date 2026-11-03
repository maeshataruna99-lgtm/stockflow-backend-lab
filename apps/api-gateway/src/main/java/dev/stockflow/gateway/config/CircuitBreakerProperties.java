package dev.stockflow.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "stockflow.gateway.circuit-breaker")
public class CircuitBreakerProperties {

    /** Number of recent calls the failure rate is computed over. */
    private int slidingWindowSize = 10;

    /** Calls needed before the failure rate is evaluated. */
    private int minimumNumberOfCalls = 5;

    /** Percentage of failed calls that opens the circuit. */
    private float failureRateThreshold = 50;

    /** How long the circuit stays open before probing the downstream again. */
    private Duration waitDurationInOpenState = Duration.ofSeconds(10);

    /** Per-call timeout; a slower downstream counts as a failure. */
    private Duration timeout = Duration.ofSeconds(2);

    public int getSlidingWindowSize() {
        return slidingWindowSize;
    }

    public void setSlidingWindowSize(int slidingWindowSize) {
        this.slidingWindowSize = slidingWindowSize;
    }

    public int getMinimumNumberOfCalls() {
        return minimumNumberOfCalls;
    }

    public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
        this.minimumNumberOfCalls = minimumNumberOfCalls;
    }

    public float getFailureRateThreshold() {
        return failureRateThreshold;
    }

    public void setFailureRateThreshold(float failureRateThreshold) {
        this.failureRateThreshold = failureRateThreshold;
    }

    public Duration getWaitDurationInOpenState() {
        return waitDurationInOpenState;
    }

    public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
        this.waitDurationInOpenState = waitDurationInOpenState;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
