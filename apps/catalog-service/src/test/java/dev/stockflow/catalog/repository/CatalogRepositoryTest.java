package dev.stockflow.catalog.repository;

import dev.stockflow.catalog.AbstractCatalogIntegrationTest;
import dev.stockflow.catalog.domain.Category;
import dev.stockflow.catalog.domain.Product;
import dev.stockflow.catalog.domain.Sku;
import dev.stockflow.catalog.domain.Unit;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Phase 3 JPA foundation against a real PostgreSQL container:
 * the deterministic Phase 2 seed is visible through JPA, and a fresh
 * insert round-trips with generated identity, database-managed audit
 * timestamps and relations intact.
 */
@Transactional
class CatalogRepositoryTest extends AbstractCatalogIntegrationTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UnitRepository unitRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SkuRepository skuRepository;

    @Test
    void seedDataIsVisibleThroughJpa() {
        assertThat(categoryRepository.count()).isEqualTo(2);
        assertThat(unitRepository.count()).isEqualTo(2);
        assertThat(productRepository.count()).isEqualTo(2);
        assertThat(skuRepository.count()).isEqualTo(2);

        Product seeded = productRepository.findById(1L).orElseThrow();
        assertThat(seeded.getSku()).isEqualTo("SPK-X1");
        assertThat(seeded.getCategory().getCode()).isEqualTo("ELECTRONICS");
        assertThat(seeded.getUnit().getCode()).isEqualTo("PCS");
        assertThat(seeded.getCreatedAt()).isNotNull();
    }

    @Test
    void newProductRoundTripsWithIdentityAndTimestamps() {
        Category category = categoryRepository.save(new Category("BOOKS", "Books"));
        Unit unit = unitRepository.save(new Unit("BOX", "Box"));
        Product product = productRepository.save(
                new Product("BOK-001", "Clean Code", "A book about code", category, unit));
        skuRepository.save(new Sku(product, "BOK-001-HC", "9780132350884"));

        // flush writes the row, clear drops the persistence context, so
        // findById must re-select from the database and expose the
        // database-generated identity and now() audit timestamps.
        entityManager.flush();
        entityManager.clear();

        Optional<Product> reloaded = productRepository.findById(product.getId());

        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getId()).isNotNull().isPositive();
        assertThat(reloaded.get().getCategory().getName()).isEqualTo("Books");
        assertThat(reloaded.get().getUnit().getName()).isEqualTo("Box");
        assertThat(reloaded.get().getCreatedAt()).isNotNull();
        assertThat(reloaded.get().getUpdatedAt()).isNotNull();
        assertThat(skuRepository.count()).isEqualTo(3);
    }
}
