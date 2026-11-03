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

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }
}
