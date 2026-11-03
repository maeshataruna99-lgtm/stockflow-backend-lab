package dev.stockflow.warehouse.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Create-stock request: how many units of a product sit at a location. */
public record StockLevelRequest(
        @NotNull Long locationId,
        @NotNull Long productId,
        @Min(0) int quantity) {
}
