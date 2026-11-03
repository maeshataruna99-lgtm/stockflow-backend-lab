package dev.stockflow.warehouse.api.dto;

import dev.stockflow.warehouse.domain.StockLevel;

import java.time.Instant;

/** Public stock representation; JPA entities are never serialized. */
public record StockLevelResponse(
        Long id,
        Long locationId,
        Long productId,
        int quantity,
        Instant updatedAt) {

    public static StockLevelResponse from(StockLevel stock) {
        return new StockLevelResponse(
                stock.getId(),
                stock.getLocation().getId(),
                stock.getProductId(),
                stock.getQuantity(),
                stock.getUpdatedAt());
    }
}
