package dev.stockflow.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "stockflow.security.rate-limit")
public class RateLimitProperties {

    /**
     * How many tokens are replenished per second.
     */
    private int replenishRate = 10;

    /**
     * Maximum burst capacity.
     */
    private int burstCapacity = 20;

    /**
     * How many tokens each request consumes.
     */
    private int requestedTokens = 1;

    public int getReplenishRate() {
        return replenishRate;
    }

    public void setReplenishRate(int replenishRate) {
        this.replenishRate = replenishRate;
    }

    public int getBurstCapacity() {
        return burstCapacity;
    }

    public void setBurstCapacity(int burstCapacity) {
        this.burstCapacity = burstCapacity;
    }

    public int getRequestedTokens() {
        return requestedTokens;
    }

    public void setRequestedTokens(int requestedTokens) {
        this.requestedTokens = requestedTokens;
    }
}
