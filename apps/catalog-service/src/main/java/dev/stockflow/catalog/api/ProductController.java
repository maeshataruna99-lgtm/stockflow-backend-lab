package dev.stockflow.catalog.api;

import dev.stockflow.catalog.api.dto.ProductRequest;
import dev.stockflow.catalog.api.dto.ProductResponse;
import dev.stockflow.catalog.api.dto.ProductUpdateRequest;
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
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService productService;

    ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    Page<ProductResponse> list(@RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @RequestParam(required = false) Boolean active,
                               @RequestParam(required = false) Long categoryId,
                               @RequestParam(required = false) String search) {
        Pageable pageable = Pagination.of(page, size);
        return productService.search(active, categoryId, search, pageable);
    }

    @GetMapping("/{id}")
    ProductResponse get(@PathVariable Long id) {
        return productService.get(id);
    }

    @PostMapping
    ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductResponse created = productService.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/products/" + created.id()))
                .body(created);
    }

    @PatchMapping("/{id}")
    ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductUpdateRequest request) {
        return productService.update(id, request);
    }
}
