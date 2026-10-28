package dev.stockflow.catalog.api;

import dev.stockflow.catalog.api.dto.ProductRequest;
import dev.stockflow.catalog.api.dto.ProductResponse;
import dev.stockflow.catalog.api.dto.ProductUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
 * Product API (Phase 3 minimum). Pagination: page (0-based) and size,
 * default 20, clamped to a maximum of 100 (see {@link Pagination}).
 * Filters (active/categoryId/search) are optional and combined through
 * the documented JPQL query in ProductRepository#searchAll.
 * <p>
 * The swagger annotations below document exactly the behavior this
 * service has - the same 400/404/409 contract the tests and the live
 * runs prove (docs/STATUS.md), so "OpenAPI reflects actual behavior"
 * holds by construction.
 */
@Tag(name = "Products", description = "Product master data (catalog-service)")
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService productService;

    ProductController(ProductService productService) {
        this.productService = productService;
    }

    @Operation(summary = "List products",
            description = "Paginated; page is 0-based, size defaults to 20 and is "
                    + "clamped to 1..100. Filters are optional and combined "
                    + "through the documented JPQL query.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of products")
    })
    @GetMapping
    Page<ProductResponse> list(
            @Parameter(description = "0-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "page size, 1..100 (default 20)") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "filter: only active/inactive products")
            @RequestParam(required = false) Boolean active,
            @Parameter(description = "filter: products of this category")
            @RequestParam(required = false) Long categoryId,
            @Parameter(description = "filter: case-insensitive substring on name")
            @RequestParam(required = false) String search) {
        Pageable pageable = Pagination.of(page, size);
        return productService.search(active, categoryId, search, pageable);
    }

    @Operation(summary = "Get one product")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The product"),
            @ApiResponse(responseCode = "404",
                    description = "No product with this id (application/problem+json)")
    })
    @GetMapping("/{id}")
    ProductResponse get(@PathVariable Long id) {
        return productService.get(id);
    }

    @Operation(summary = "Create a product")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; Location header "
                    + "holds the new resource URI, the body carries audit timestamps"),
            @ApiResponse(responseCode = "400",
                    description = "Bean validation failed; problem+json with an "
                            + "'errors' field map"),
            @ApiResponse(responseCode = "409",
                    description = "SKU already exists (application/problem+json)")
    })
    @PostMapping
    ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductResponse created = productService.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/products/" + created.id()))
                .body(created);
    }

    @Operation(summary = "Partially update a product",
            description = "Provided fields replace current values; omitted fields "
                    + "are left untouched.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated product with "
                    + "the flushed updatedAt"),
            @ApiResponse(responseCode = "400",
                    description = "Bean validation failed (application/problem+json)"),
            @ApiResponse(responseCode = "404",
                    description = "No product with this id (application/problem+json)")
    })
    @PatchMapping("/{id}")
    ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductUpdateRequest request) {
        return productService.update(id, request);
    }
}
