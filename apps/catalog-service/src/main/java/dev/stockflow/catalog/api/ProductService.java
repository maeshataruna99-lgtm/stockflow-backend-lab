package dev.stockflow.catalog.api;

import dev.stockflow.catalog.api.dto.ProductRequest;
import dev.stockflow.catalog.api.dto.ProductResponse;
import dev.stockflow.catalog.api.dto.ProductUpdateRequest;
import dev.stockflow.catalog.domain.Category;
import dev.stockflow.catalog.domain.Product;
import dev.stockflow.catalog.domain.Unit;
import dev.stockflow.catalog.repository.CategoryRepository;
import dev.stockflow.catalog.repository.ProductRepository;
import dev.stockflow.catalog.repository.UnitRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product use cases. Read methods map entities to DTOs inside the
 * transaction (open-in-view is off); writes enforce the documented
 * failure contract: unknown category/unit -> 404, duplicate SKU -> 409.
 */
@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final UnitRepository unitRepository;

    ProductService(ProductRepository productRepository,
                   CategoryRepository categoryRepository,
                   UnitRepository unitRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.unitRepository = unitRepository;
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> search(Boolean active, Long categoryId, String search, Pageable pageable) {
        String normalizedSearch = (search == null || search.isBlank()) ? null : search;
        return productRepository.searchAll(active, categoryId, normalizedSearch, pageable)
                .map(ProductResponse::from);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Long id) {
        return productRepository.findById(id)
                .map(ProductResponse::from)
                .orElseThrow(() -> ResourceNotFoundException.of("product", id));
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        if (productRepository.existsBySku(request.sku())) {
            throw new DuplicateResourceException("sku '" + request.sku() + "' already exists");
        }
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> ResourceNotFoundException.of("category", request.categoryId()));
        Unit unit = unitRepository.findById(request.unitId())
                .orElseThrow(() -> ResourceNotFoundException.of("unit", request.unitId()));

        Product product = new Product(request.sku(), request.name(), request.description(), category, unit);
        if (request.active() != null) {
            product.setActive(request.active());
        }
        Product saved = productRepository.save(product);
        return ProductResponse.from(saved);
    }

    @Transactional
    public ProductResponse update(Long id, ProductUpdateRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("product", id));

        if (request.categoryId() != null) {
            product.setCategory(categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> ResourceNotFoundException.of("category", request.categoryId())));
        }
        if (request.unitId() != null) {
            product.setUnit(unitRepository.findById(request.unitId())
                    .orElseThrow(() -> ResourceNotFoundException.of("unit", request.unitId())));
        }
        if (request.name() != null) {
            product.setName(request.name());
        }
        if (request.description() != null) {
            product.setDescription(request.description());
        }
        if (request.active() != null) {
            product.setActive(request.active());
        }

        // saveAndFlush (not save): the @UpdateTimestamp value is applied
        // during flush, so the returned DTO carries the real audit time
        // instead of the pre-update value (live-run evidence, STATUS.md).
        Product saved = productRepository.saveAndFlush(product);
        return ProductResponse.from(saved);
    }
}
