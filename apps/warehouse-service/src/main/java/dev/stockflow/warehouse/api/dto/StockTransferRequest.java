package dev.stockflow.warehouse.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Move units from one stock row to another. */
public record StockTransferRequest(
        @NotNull Long fromId,
        @NotNull Long toId,
        @Min(1) int quantity) {

    @AssertTrue(message = "fromId and toId must be different rows")
    boolean isDifferentRows() {
        return fromId == null || !fromId.equals(toId);
    }
}
