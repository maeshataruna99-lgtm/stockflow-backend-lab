package dev.stockflow.catalog.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Partial update request (PATCH): every field is optional, provided
 * fields replace the current value. A name must be non-blank when
 * present (min = 1); length limits mirror the Flyway V1 schema.
 */
public record ProductUpdateRequest(
        @Size(min = 1, max = 160) String name,
        String description,
        Long categoryId,
        Long unitId,
        Boolean active) {
}
