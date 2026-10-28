package dev.stockflow.catalog.api;

import dev.stockflow.catalog.api.dto.CategoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Category API (Phase 3 minimum: read only). Pagination: page (0-based)
 * and size, default 20, clamped to a maximum of 100.
 */
@Tag(name = "Categories", description = "Category master data (catalog-service)")
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @Operation(summary = "List categories",
            description = "Paginated; page is 0-based, size defaults to 20 and is "
                    + "clamped to 1..100.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of categories")
    })
    @GetMapping
    Page<CategoryResponse> list(
            @Parameter(description = "0-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "page size, 1..100 (default 20)") @RequestParam(defaultValue = "20") int size) {
        return categoryService.list(Pagination.of(page, size));
    }
}
