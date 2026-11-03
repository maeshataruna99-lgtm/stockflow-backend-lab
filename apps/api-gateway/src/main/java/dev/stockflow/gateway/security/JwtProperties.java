package dev.stockflow.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "stockflow.security.jwt")
public class JwtProperties {

    /**
     * Secret key for HMAC-SHA256 signing (minimum 32 characters / 256 bits).
     */
    private String secret = "stockflow-dev-secret-key-must-be-at-least-32-bytes-long-for-hs256!";

    /**
     * Expiration duration in seconds (default: 3600 seconds = 1 hour).
     */
    private long expirationSeconds = 3600;

    /**
     * Issuer identifier for JWT claims.
     */
    private String issuer = "stockflow-gateway";

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    public void setExpirationSeconds(long expirationSeconds) {
        this.expirationSeconds = expirationSeconds;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }
}
