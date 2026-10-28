package dev.stockflow.catalog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create-product request. Length limits mirror the Flyway V1 schema
 * (sku VARCHAR(32), name VARCHAR(160)); bean-validation failures are
 * reported as a documented 400 problem+json response.
 */
public record ProductRequest(
        @NotBlank @Size(max = 32) String sku,
        @NotBlank @Size(max = 160) String name,
        String description,
        @NotNull Long categoryId,
        @NotNull Long unitId,
        Boolean active) {
}
