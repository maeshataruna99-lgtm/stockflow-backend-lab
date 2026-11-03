package dev.stockflow.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Index lab on its own PostgreSQL container (same image as the service, schema from the service's Flyway migrations)
 * with 200,000 products in 100 categories plus one rare category of 50 products stored last. The queries are the ones
 * Hibernate sends for GET /api/v1/products, with the parameters bound as $1..$4 (the same statement, the repeated JDBC
 * placeholders folded into one number). Every plan is a real EXPLAIN (ANALYZE, BUFFERS) of that statement; timings are
 * the median of 5 runs after a warm-up. The tests run in order because each step adds indexes to the previous state;
 * the last stage applies the real migration V3 and must give the same plans as the hand-made indexes.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductIndexPlanTest {

    private static final int PRODUCTS = 200_000;
    private static final int CATEGORIES = 100;
    private static final int RARE_PRODUCTS = 50;

    private static final String SELECT = """
            select p.id, p.active, p.category_id, c.id, c.code, p.name, p.sku, p.unit_id, u.id, u.code
            from products p join categories c on c.id = p.category_id join units u on u.id = p.unit_id
            where (cast($1 as boolean) is null or p.active = $1)
              and (cast($2 as bigint) is null or p.category_id = $2)
              and (cast($3 as varchar) is null
                   or lower(p.name) like lower('%' || cast($3 as varchar) || '%') escape ''
                   or lower(p.sku) like lower('%' || cast($3 as varchar) || '%') escape '')
            fetch first $4 rows only""";

    private static final String COUNT = """
            select count(p.id) from products p
            where (cast($1 as boolean) is null or p.active = $1)
              and (cast($2 as bigint) is null or p.category_id = $2)
              and (cast($3 as varchar) is null
                   or lower(p.name) like lower('%' || cast($3 as varchar) || '%') escape ''
                   or lower(p.sku) like lower('%' || cast($3 as varchar) || '%') escape '')""";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static PostgreSQLContainer<?> postgres;
    private static SingleConnectionDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static long denseCategory;
    private static long rareCategory;

    private record Measured(String nodes, double medianMillis) {
        boolean uses(String node) {
            return nodes.contains(node);
        }
    }

    private record Stage(Measured listDense, Measured listDenseGeneric, Measured countDense, Measured listRare,
                         Measured listSearch, Measured countSearch) {
    }

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target(target).load();
    }

    @BeforeAll
    static void startDatabaseAndLoadData() {
        postgres = new PostgreSQLContainer<>("postgres:16.15-alpine");
        postgres.start();
        flyway("2").migrate(); // the schema before this unit: primary keys and unique keys only
        dataSource = new SingleConnectionDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO categories (code, name) SELECT 'LAB-' || g, 'lab category ' || g "
                + "FROM generate_series(1, ?) g", CATEGORIES);
        jdbc.update("INSERT INTO categories (code, name) VALUES ('LAB-RARE', 'rare lab category')");
        denseCategory = jdbc.queryForObject("SELECT id FROM categories WHERE code = 'LAB-17'", Long.class);
        rareCategory = jdbc.queryForObject("SELECT id FROM categories WHERE code = 'LAB-RARE'", Long.class);
        jdbc.update("INSERT INTO products (sku, name, category_id, unit_id) "
                + "SELECT 'LAB-' || g, 'item ' || g || ' ' || md5(g::text), "
                + "(SELECT min(id) FROM categories WHERE code = 'LAB-1') + (g % ?), 1 "
                + "FROM generate_series(1, ?) g", CATEGORIES, PRODUCTS);
        jdbc.update("INSERT INTO products (sku, name, category_id, unit_id) "
                + "SELECT 'RARE-' || g, 'rare ' || g || ' ' || md5((-g)::text), ?, 1 "
                + "FROM generate_series(1, ?) g", rareCategory, RARE_PRODUCTS);
        jdbc.execute("ANALYZE");
    }

    @AfterAll
    static void stopDatabase() {
        dataSource.destroy();
        postgres.stop();
    }

    /** Node types of the plan, top to bottom, with the relation or index each one reads. */
    private static void collect(JsonNode node, List<String> out) {
        String label = node.get("Node Type").asText();
        if (node.has("Index Name")) {
            label += "(" + node.get("Index Name").asText() + ")";
        } else if (node.has("Relation Name")) {
            label += "(" + node.get("Relation Name").asText() + ")";
        }
        out.add(label);
        if (node.has("Plans")) {
            node.get("Plans").forEach(child -> collect(child, out));
        }
    }

    private static Measured explain(String statement, String planMode, String arguments) throws Exception {
        jdbc.execute("SET plan_cache_mode = " + planMode);
        jdbc.execute("DEALLOCATE ALL");
        jdbc.execute("PREPARE q AS " + statement);
        List<Double> times = new ArrayList<>();
        String nodes = "";
        for (int run = 0; run < 6; run++) {
            String json = jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) EXECUTE q(" + arguments + ")",
                    String.class);
            JsonNode root = JSON.readTree(json).get(0);
            if (run > 0) { // run 0 is the warm-up
                times.add(root.get("Execution Time").asDouble());
            }
            List<String> collected = new ArrayList<>();
            collect(root.get("Plan"), collected);
            nodes = String.join(" > ", collected);
        }
        Collections.sort(times);
        return new Measured(nodes, times.get(times.size() / 2));
    }

    private static void report(String stage, String label, Measured m) {
        System.out.printf("PLAN %-12s %-26s %9.3f ms  %s%n", stage, label, m.medianMillis(), m.nodes());
    }

    private static Stage measure(String stage) throws Exception {
        String dense = "null, " + denseCategory + ", null";
        String rare = "null, " + rareCategory + ", null";
        String search = "null, null, 'f00ba'";
        Stage s = new Stage(
                explain(SELECT, "force_custom_plan", dense + ", 20"),
                explain(SELECT, "force_generic_plan", dense + ", 20"),
                explain(COUNT, "force_custom_plan", dense),
                explain(SELECT, "force_custom_plan", rare + ", 20"),
                explain(SELECT, "force_custom_plan", search + ", 20"),
                explain(COUNT, "force_custom_plan", search));
        report(stage, "list, dense category", s.listDense());
        report(stage, "list, dense, generic plan", s.listDenseGeneric());
        report(stage, "count, dense category", s.countDense());
        report(stage, "list, rare category", s.listRare());
        report(stage, "list, search f00ba", s.listSearch());
        report(stage, "count, search f00ba", s.countSearch());
        return s;
    }

    private static Stage baseline;
    private static Stage withCategoryIndex;

    @Test
    @Order(1)
    @DisplayName("Baseline: only primary keys and unique keys; every filter scans the table")
    void baseline() throws Exception {
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM products", Integer.class);
        System.out.println("rows in products: " + rows + ", dense category rows: "
                + jdbc.queryForObject("SELECT count(*) FROM products WHERE category_id = ?", Integer.class, denseCategory)
                + ", rare category rows: " + RARE_PRODUCTS);
        assertThat(rows).isEqualTo(PRODUCTS + RARE_PRODUCTS + 2);

        baseline = measure("0-none");

        assertThat(baseline.countDense().uses("Seq Scan(products)")).isTrue();
        assertThat(baseline.listRare().uses("Seq Scan(products)")).isTrue();
        assertThat(baseline.listSearch().uses("Seq Scan(products)")).isTrue();
        assertThat(baseline.countSearch().uses("Seq Scan(products)")).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("A btree on category_id helps the count and the rare category, not the dense list and not the search")
    void afterCategoryIndex() throws Exception {
        jdbc.execute("CREATE INDEX products_category_id_idx ON products (category_id)");
        jdbc.execute("ANALYZE products");

        withCategoryIndex = measure("1-category_id");

        assertThat(withCategoryIndex.countDense().uses("Index(products_category_id_idx)")
                || withCategoryIndex.countDense().uses("Bitmap Index Scan(products_category_id_idx)")).isTrue();
        assertThat(withCategoryIndex.countDense().medianMillis()).isLessThan(baseline.countDense().medianMillis() / 3);
        assertThat(withCategoryIndex.listRare().uses("Bitmap Index Scan(products_category_id_idx)")).isTrue();
        assertThat(withCategoryIndex.listRare().medianMillis()).isLessThan(baseline.listRare().medianMillis() / 3);
        // the search is a contains-match on lower(name): a btree on another column leaves its plan unchanged
        assertThat(withCategoryIndex.listSearch().uses("Seq Scan(products)")).isTrue();
        assertThat(withCategoryIndex.countSearch().uses("Seq Scan(products)")).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("Trigram indexes turn the contains-search from a table scan into an index lookup")
    void afterTrigramIndexes() throws Exception {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        jdbc.execute("CREATE INDEX products_name_trgm_idx ON products USING gin (lower(name) gin_trgm_ops)");
        jdbc.execute("CREATE INDEX products_sku_trgm_idx ON products USING gin (lower(sku) gin_trgm_ops)");
        jdbc.execute("ANALYZE products");

        Stage withTrigram = measure("2-trigram");

        assertThat(withTrigram.listSearch().uses("Bitmap Index Scan(products_name_trgm_idx)")).isTrue();
        assertThat(withTrigram.countSearch().uses("Bitmap Index Scan(products_sku_trgm_idx)")).isTrue();
        assertThat(withTrigram.listSearch().medianMillis()).isLessThan(withCategoryIndex.listSearch().medianMillis() / 20);
        assertThat(withTrigram.countSearch().medianMillis()).isLessThan(withCategoryIndex.countSearch().medianMillis() / 20);
    }

    @Test
    @Order(4)
    @DisplayName("The real migration V3 produces the same plans as the hand-made indexes")
    void migrationV3GivesTheSamePlans() throws Exception {
        jdbc.execute("DROP INDEX products_category_id_idx, products_name_trgm_idx, products_sku_trgm_idx");
        jdbc.execute("DROP EXTENSION pg_trgm");
        flyway("3").migrate();
        jdbc.execute("ANALYZE products");

        Stage shipped = measure("3-migration");

        assertThat(shipped.countDense().uses("products_category_id_idx")).isTrue();
        assertThat(shipped.listRare().uses("Bitmap Index Scan(products_category_id_idx)")).isTrue();
        assertThat(shipped.listSearch().uses("products_name_trgm_idx")).isTrue();
        assertThat(shipped.countSearch().uses("products_sku_trgm_idx")).isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("With the default plan_cache_mode, the plan of the catch-all count after 10 executions")
    void planCacheModeAuto() throws Exception {
        jdbc.execute("SET plan_cache_mode = auto");
        jdbc.execute("DEALLOCATE ALL");
        jdbc.execute("PREPARE q AS " + COUNT);
        List<String> top = new ArrayList<>();
        for (int run = 1; run <= 10; run++) {
            String json = jdbc.queryForObject(
                    "EXPLAIN (FORMAT JSON) EXECUTE q(null, " + denseCategory + ", null)", String.class);
            List<String> nodes = new ArrayList<>();
            collect(JSON.readTree(json).get(0).get("Plan"), nodes);
            top.add(nodes.stream().filter(n -> n.contains("Scan")).findFirst().orElse("?"));
        }
        System.out.println("PLAN auto mode, count of the dense category, executions 1..10: " + top);
        assertThat(top).hasSize(10);
    }
}
