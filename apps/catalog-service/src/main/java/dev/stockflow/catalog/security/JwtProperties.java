package dev.stockflow.catalog.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the gateway's tokens are checked. The secret has no default: the service fails to start without it.
 *
 * @param secret HS256 key shared with the gateway, at least 32 bytes
 * @param issuer value the "iss" claim must have
 */
@ConfigurationProperties(prefix = "stockflow.security.jwt")
public record JwtProperties(String secret, @DefaultValue("stockflow-gateway") String issuer) {
}
