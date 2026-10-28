package dev.stockflow.warehouse.api;

import dev.stockflow.warehouse.api.dto.StorageLocationRequest;
import dev.stockflow.warehouse.api.dto.StorageLocationResponse;
import dev.stockflow.warehouse.api.dto.WarehouseRequest;
import dev.stockflow.warehouse.api.dto.WarehouseResponse;
import dev.stockflow.warehouse.api.dto.WarehouseUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * <p>
 * The swagger annotations below document exactly the behavior this
 * service has - the same 400/404/409 contract the tests and the live
 * runs prove (docs/STATUS.md), so "OpenAPI reflects actual behavior"
 * holds by construction.
 */
@Tag(name = "Warehouses", description = "Warehouse and storage-location master data (warehouse-service)")
@RestController
@RequestMapping("/api/v1/warehouses")
public class WarehouseController {

    private final WarehouseService warehouseService;

    WarehouseController(WarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    @Operation(summary = "List warehouses",
            description = "Paginated; page is 0-based, size defaults to 20 and is "
                    + "clamped to 1..100.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of warehouses")
    })
    @GetMapping
    Page<WarehouseResponse> list(
            @Parameter(description = "0-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "page size, 1..100 (default 20)") @RequestParam(defaultValue = "20") int size) {
        return warehouseService.list(Pagination.of(page, size));
    }

    @Operation(summary = "Get one warehouse")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The warehouse"),
            @ApiResponse(responseCode = "404",
                    description = "No warehouse with this id (application/problem+json)")
    })
    @GetMapping("/{id}")
    WarehouseResponse get(@PathVariable Long id) {
        return warehouseService.get(id);
    }

    @Operation(summary = "Create a warehouse")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; Location header "
                    + "holds the new resource URI, the body carries audit timestamps"),
            @ApiResponse(responseCode = "400",
                    description = "Bean validation failed; problem+json with an "
                            + "'errors' field map"),
            @ApiResponse(responseCode = "409",
                    description = "Warehouse code already exists (application/problem+json)")
    })
    @PostMapping
    ResponseEntity<WarehouseResponse> create(@Valid @RequestBody WarehouseRequest request) {
        WarehouseResponse created = warehouseService.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/warehouses/" + created.id()))
                .body(created);
    }

    @Operation(summary = "Partially update a warehouse",
            description = "Provided fields replace current values; omitted fields "
                    + "are left untouched.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated warehouse with "
                    + "the flushed updatedAt"),
            @ApiResponse(responseCode = "400",
                    description = "Bean validation failed (application/problem+json)"),
            @ApiResponse(responseCode = "404",
                    description = "No warehouse with this id (application/problem+json)"),
            @ApiResponse(responseCode = "409",
                    description = "Renaming to an existing code (application/problem+json)")
    })
    @PatchMapping("/{id}")
    WarehouseResponse update(@PathVariable Long id, @Valid @RequestBody WarehouseUpdateRequest request) {
        return warehouseService.update(id, request);
    }

    @Operation(summary = "List storage locations of one warehouse",
            description = "Paginated; a missing warehouse is a 404, not an empty page.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of locations"),
            @ApiResponse(responseCode = "404",
                    description = "No warehouse with this id (application/problem+json)")
    })
    @GetMapping("/{id}/locations")
    Page<StorageLocationResponse> listLocations(
            @PathVariable Long id,
            @Parameter(description = "0-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "page size, 1..100 (default 20)") @RequestParam(defaultValue = "20") int size) {
        return warehouseService.listLocations(id, Pagination.of(page, size));
    }

    @Operation(summary = "Create a storage location in a warehouse",
            description = "Location codes are unique per warehouse: the same code "
                    + "may exist in a different warehouse.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; Location header "
                    + "holds the new resource URI"),
            @ApiResponse(responseCode = "400",
                    description = "Bean validation failed (application/problem+json)"),
            @ApiResponse(responseCode = "404",
                    description = "No warehouse with this id (application/problem+json)"),
            @ApiResponse(responseCode = "409",
                    description = "Location code already exists in this warehouse "
                            + "(application/problem+json)")
    })
    @PostMapping("/{id}/locations")
    ResponseEntity<StorageLocationResponse> createLocation(@PathVariable Long id,
                                                           @Valid @RequestBody StorageLocationRequest request) {
        StorageLocationResponse created = warehouseService.createLocation(id, request.code());
        return ResponseEntity
                .created(URI.create("/api/v1/warehouses/" + id + "/locations/" + created.id()))
                .body(created);
    }
}
