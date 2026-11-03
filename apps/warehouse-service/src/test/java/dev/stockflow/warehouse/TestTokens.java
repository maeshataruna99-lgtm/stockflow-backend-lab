package dev.stockflow.warehouse;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** Signs tokens the way the gateway does (HS256, claims sub, roles, iss, iat, exp). */
public final class TestTokens {

    /** Same value as stockflow.security.jwt.secret in src/test/resources/application.yml. */
    public static final String SECRET = "test-only-signing-key-not-a-real-secret-0123456789";
    public static final String ISSUER = "stockflow-gateway";

    private TestTokens() {
    }

    public static String token(String subject, List<String> roles) {
        return custom(subject, roles, ISSUER, Instant.now().plusSeconds(600), SECRET);
    }

    public static String custom(String subject, List<String> roles, String issuer, Instant expiry, String secret) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(issuer)
                    .issueTime(new Date())
                    .expirationTime(Date.from(expiry))
                    .claim("roles", roles)
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
