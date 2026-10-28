package dev.stockflow.warehouse.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Partial update request (PATCH): every field is optional, provided
 * fields replace the current value. name/code must be non-blank when
 * present (min = 1); length limits mirror the Flyway V1 schema.
 */
public record WarehouseUpdateRequest(
        @Size(min = 1, max = 32) String code,
        @Size(min = 1, max = 160) String name,
        String address,
        Boolean active) {
}
