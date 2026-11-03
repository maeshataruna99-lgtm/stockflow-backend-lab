package dev.stockflow.warehouse.api.dto;

import jakarta.validation.constraints.Min;

/** Withdraw request: at least one unit. */
public record StockWithdrawRequest(@Min(1) int quantity) {
}
