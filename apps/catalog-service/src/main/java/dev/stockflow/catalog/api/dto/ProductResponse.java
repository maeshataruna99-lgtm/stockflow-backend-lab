package dev.stockflow.catalog.api.dto;

import dev.stockflow.catalog.domain.Product;

import java.time.Instant;

/**
 * Public product representation. JPA entities are never serialized
 * (Phase 3 acceptance criterion); the mapper runs inside a service
 * transaction so lazy category/unit references resolve while the
 * persistence context is open (open-in-view is off).
 */
public record ProductResponse(
        Long id,
        String sku,
        String name,
        String description,
        Long categoryId,
        String categoryCode,
        Long unitId,
        String unitCode,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getCategory().getId(),
                product.getCategory().getCode(),
                product.getUnit().getId(),
                product.getUnit().getCode(),
                product.isActive(),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}
