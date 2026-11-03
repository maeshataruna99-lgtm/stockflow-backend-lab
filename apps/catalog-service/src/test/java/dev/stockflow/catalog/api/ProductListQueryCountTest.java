package dev.stockflow.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stockflow.catalog.AbstractCatalogIntegrationTest;
import dev.stockflow.catalog.api.dto.ProductRequest;
import dev.stockflow.catalog.api.dto.ProductResponse;
import dev.stockflow.catalog.domain.Category;
import dev.stockflow.catalog.domain.Unit;
import dev.stockflow.catalog.repository.CategoryRepository;
import dev.stockflow.catalog.repository.ProductRepository;
import dev.stockflow.catalog.repository.UnitRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * N+1 on the product list: the page of products is one query, and mapping each product to its response reads the
 * product's category and unit, which are lazy. Counting the SQL statements Hibernate prepares shows whether the list
 * costs a fixed number of queries or one or two more for every product on the page.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ProductListQueryCountTest extends AbstractCatalogIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UnitRepository unitRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ProductRepository productRepository;

    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> categoryIds = new ArrayList<>();
    private final List<Long> unitIds = new ArrayList<>();

    /**
     * The singleton database is shared and CatalogRepositoryTest counts the seeded categories and units (nothing else
     * writes them), so this test removes everything it created, products first because of the foreign keys.
     */
    @AfterEach
    void removeCreatedRows() {
        productRepository.deleteAllById(productIds);
        categoryRepository.deleteAllById(categoryIds);
        unitRepository.deleteAllById(unitIds);
    }

    /** Creates {@code count} products, each with its own category and its own unit; returns the search token. */
    private String createProducts(int count) {
        String token = "n1" + (System.nanoTime() % 1_000_000_000L);
        for (int i = 0; i < count; i++) {
            Category category = categoryRepository.save(new Category(token + "c" + i, "category " + i));
            Unit unit = unitRepository.save(new Unit("u" + i + "x" + token.substring(2), "unit " + i));
            categoryIds.add(category.getId());
            unitIds.add(unit.getId());
            productIds.add(productService.create(new ProductRequest(token + "-" + i, token + " product " + i, null,
                    category.getId(), unit.getId(), true)).id());
        }
        return token;
    }

    private long statementsForList(String token, int pageSize) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        Page<ProductResponse> page = productService.search(null, null, token, PageRequest.of(0, pageSize));
        assertThat(page.getContent()).isNotEmpty().allSatisfy(p -> {
            assertThat(p.categoryCode()).startsWith(token);
            assertThat(p.unitCode()).isNotBlank();
        });
        return statistics.getPrepareStatementCount();
    }

    @Test
    @DisplayName("Listing 30 products with 30 different categories and units costs 2 statements, not 62")
    void listCostsAFixedNumberOfStatements() {
        String token = createProducts(30);

        long statements = statementsForList(token, 30);

        System.out.println("product list, page of 30: " + statements + " SQL statements");
        assertThat(statements).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("The statement count does not grow with the page size")
    void statementCountIsIndependentOfThePageSize() {
        String token = createProducts(30);

        long small = statementsForList(token, 5);
        long large = statementsForList(token, 30);

        System.out.println("product list: page of 5 = " + small + " statements, page of 30 = " + large);
        assertThat(small).isLessThanOrEqualTo(2);
        assertThat(large).isEqualTo(small);
    }
}
