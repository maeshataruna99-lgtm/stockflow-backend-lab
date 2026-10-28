package dev.stockflow.warehouse.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Create-storage-location request; code is unique per warehouse. */
public record StorageLocationRequest(
        @NotBlank @Size(max = 64) String code) {
}
