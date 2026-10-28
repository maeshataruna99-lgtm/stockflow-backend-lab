package dev.stockflow.catalog.api.dto;

import dev.stockflow.catalog.domain.Category;

import java.time.Instant;

/** Public category representation; never the JPA entity. */
public record CategoryResponse(
        Long id,
        String code,
        String name,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getCode(),
                category.getName(),
                category.isActive(),
                category.getCreatedAt(),
                category.getUpdatedAt());
    }
}
