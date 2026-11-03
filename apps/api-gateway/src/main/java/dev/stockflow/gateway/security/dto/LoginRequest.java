package dev.stockflow.gateway.security.dto;

public record LoginRequest(
        String username,
        String password
) {
}
