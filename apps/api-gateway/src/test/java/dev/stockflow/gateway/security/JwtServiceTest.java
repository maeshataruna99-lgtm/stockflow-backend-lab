package dev.stockflow.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    @Test
    void failsFastWhenSecretIsMissing() {
        assertThatThrownBy(() -> new JwtService(new JwtProperties()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signsWithHs256WhateverTheKeyLength() {
        // JJWT picks HS384 for a 48-63 byte key and HS512 from 64 bytes unless told otherwise; ADR 0006 says HS256
        for (int length : new int[] {32, 63, 80}) {
            JwtProperties properties = new JwtProperties();
            properties.setSecret("k".repeat(length));
            String token = new JwtService(properties).generateToken("user-1", List.of("ROLE_USER"));

            String header = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
            assertThat(header).contains("\"alg\":\"HS256\"");
        }
    }

    @Test
    void failsFastWhenSecretIsShorterThan256Bits() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("too-short");
        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
