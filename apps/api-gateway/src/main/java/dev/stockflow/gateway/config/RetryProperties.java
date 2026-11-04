package dev.stockflow.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "stockflow.gateway.retry")
public class RetryProperties {

    /** Extra attempts after the first one; total attempts = retries + 1. */
    private int retries = 2;

    /** Wait before the first retry; it doubles each attempt up to maxBackoff. */
    private Duration firstBackoff = Duration.ofMillis(100);

    private Duration maxBackoff = Duration.ofSeconds(1);

    /**
     * Random jitter factor 0..1: each wait is the backoff plus or minus up to this share of it, so clients that failed
     * together do not retry at the same instant. 0 switches it off.
     */
    private double jitter = 0.5;

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public Duration getFirstBackoff() {
        return firstBackoff;
    }

    public void setFirstBackoff(Duration firstBackoff) {
        this.firstBackoff = firstBackoff;
    }

    public double getJitter() {
        return jitter;
    }

    public void setJitter(double jitter) {
        this.jitter = jitter;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }
}
