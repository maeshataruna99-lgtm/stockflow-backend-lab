package dev.stockflow.catalog.repository;

import dev.stockflow.catalog.domain.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsBySku(String sku);

    /**
     * Documented JPQL query (Phase 3 acceptance criterion): filters
     * products by active flag, category and a case-insensitive search
     * over name and sku. Every filter is optional - a null parameter
     * drops its predicate, which is how GET /api/v1/products combines
     * zero or more query parameters into one query.
     *
     * Every nullable parameter is explicitly cast. Evidence (live run,
     * docs/STATUS.md): without the cast, PostgreSQL resolved an
     * unspecified null parameter inside lower(concat('%', :search, '%'))
     * as bytea and failed with "function lower(bytea) does not exist"
     * (SQLState 42883) - the cast forces typed bindings.
     */
    @Query("""
            select p from Product p
            where (cast(:active as Boolean) is null or p.active = :active)
              and (cast(:categoryId as Long) is null or p.category.id = :categoryId)
              and (cast(:search as String) is null
                   or lower(p.name) like lower(concat('%', cast(:search as String), '%'))
                   or lower(p.sku) like lower(concat('%', cast(:search as String), '%')))
            """)
    Page<Product> searchAll(@Param("active") Boolean active,
                            @Param("categoryId") Long categoryId,
                            @Param("search") String search,
                            Pageable pageable);
}
