package dev.stockflow.warehouse.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create-warehouse request. Length limits mirror the Flyway V1 schema
 * (code VARCHAR(32), name VARCHAR(160)); bean-validation failures are
 * reported as a documented 400 problem+json response.
 */
public record WarehouseRequest(
        @NotBlank @Size(max = 32) String code,
        @NotBlank @Size(max = 160) String name,
        String address) {
}
