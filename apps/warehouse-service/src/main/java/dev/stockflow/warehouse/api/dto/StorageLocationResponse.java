package dev.stockflow.warehouse.api.dto;

import dev.stockflow.warehouse.domain.StorageLocation;

import java.time.Instant;

/** Public storage-location representation; never the JPA entity. */
public record StorageLocationResponse(
        Long id,
        Long warehouseId,
        String code,
        boolean active,
        Instant createdAt) {

    public static StorageLocationResponse from(StorageLocation location) {
        return new StorageLocationResponse(
                location.getId(),
                location.getWarehouse().getId(),
                location.getCode(),
                location.isActive(),
                location.getCreatedAt());
    }
}
