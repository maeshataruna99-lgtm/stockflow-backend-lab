package dev.stockflow.warehouse.api.dto;

/** Both stock rows after a transfer. */
public record StockTransferResponse(StockLevelResponse from, StockLevelResponse to) {
}
