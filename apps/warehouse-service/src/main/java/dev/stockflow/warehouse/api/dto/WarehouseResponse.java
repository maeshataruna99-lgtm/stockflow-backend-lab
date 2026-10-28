package dev.stockflow.warehouse.api.dto;

import dev.stockflow.warehouse.domain.StorageLocation;
import dev.stockflow.warehouse.domain.Warehouse;

import java.time.Instant;

/** Public warehouse representation; JPA entities are never serialized. */
public record WarehouseResponse(
        Long id,
        String code,
        String name,
        String address,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static WarehouseResponse from(Warehouse warehouse) {
        return new WarehouseResponse(
                warehouse.getId(),
                warehouse.getCode(),
                warehouse.getName(),
                warehouse.getAddress(),
                warehouse.isActive(),
                warehouse.getCreatedAt(),
                warehouse.getUpdatedAt());
    }
}
