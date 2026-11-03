package dev.stockflow.gateway.security.dto;

public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {
}
