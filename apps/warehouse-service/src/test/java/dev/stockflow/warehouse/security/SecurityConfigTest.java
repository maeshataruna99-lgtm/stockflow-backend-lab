package dev.stockflow.warehouse.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecurityConfigTest {

    @Test
    void failsFastWhenSecretIsMissing() {
        assertThatThrownBy(() -> new SecurityConfig().jwtDecoder(new JwtProperties(null, "stockflow-gateway")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsFastWhenSecretIsShorterThan256Bits() {
        assertThatThrownBy(() -> new SecurityConfig().jwtDecoder(new JwtProperties("too-short", "stockflow-gateway")))
                .isInstanceOf(IllegalStateException.class);
    }
}
