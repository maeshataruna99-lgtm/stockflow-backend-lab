package dev.stockflow.warehouse.api;

import dev.stockflow.warehouse.api.dto.StorageLocationRequest;
import dev.stockflow.warehouse.api.dto.StorageLocationResponse;
import dev.stockflow.warehouse.api.dto.WarehouseRequest;
import dev.stockflow.warehouse.api.dto.WarehouseResponse;
import dev.stockflow.warehouse.api.dto.WarehouseUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Warehouse API (Phase 3 minimum). Pagination: page (0-based) and size,
 * default 20, clamped to a maximum of 100 (see {@link Pagination}).
 * Location codes are unique per warehouse: a duplicate within the same
 * warehouse is a 409, the same code in another warehouse is fine.
 */
@RestController
@RequestMapping("/api/v1/warehouses")
public class WarehouseController {

    private final WarehouseService warehouseService;

    WarehouseController(WarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    @GetMapping
    Page<WarehouseResponse> list(@RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return warehouseService.list(Pagination.of(page, size));
    }

    @GetMapping("/{id}")
    WarehouseResponse get(@PathVariable Long id) {
        return warehouseService.get(id);
    }

    @PostMapping
    ResponseEntity<WarehouseResponse> create(@Valid @RequestBody WarehouseRequest request) {
        WarehouseResponse created = warehouseService.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/warehouses/" + created.id()))
                .body(created);
    }

    @PatchMapping("/{id}")
    WarehouseResponse update(@PathVariable Long id, @Valid @RequestBody WarehouseUpdateRequest request) {
        return warehouseService.update(id, request);
    }

    /**
     * Paginated locations of one warehouse; a missing warehouse is a
     * documented 404, not an empty page.
     */
    @GetMapping("/{id}/locations")
    Page<StorageLocationResponse> listLocations(@PathVariable Long id,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return warehouseService.listLocations(id, Pagination.of(page, size));
    }

    @PostMapping("/{id}/locations")
    ResponseEntity<StorageLocationResponse> createLocation(@PathVariable Long id,
                                                           @Valid @RequestBody StorageLocationRequest request) {
        StorageLocationResponse created = warehouseService.createLocation(id, request.code());
        return ResponseEntity
                .created(URI.create("/api/v1/warehouses/" + id + "/locations/" + created.id()))
                .body(created);
    }
}
