package dev.stockflow.catalog.api;

import dev.stockflow.catalog.api.dto.CategoryResponse;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Category API (Phase 3 minimum: read only). Pagination: page (0-based)
 * and size, default 20, clamped to a maximum of 100.
 */
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    Page<CategoryResponse> list(@RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return categoryService.list(Pagination.of(page, size));
    }
}
