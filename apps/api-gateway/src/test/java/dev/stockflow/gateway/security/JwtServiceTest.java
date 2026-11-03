package dev.stockflow.gateway.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JwtServiceTest {

    @Test
    void failsFastWhenSecretIsMissing() {
        assertThatThrownBy(() -> new JwtService(new JwtProperties()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failsFastWhenSecretIsShorterThan256Bits() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("too-short");
        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
