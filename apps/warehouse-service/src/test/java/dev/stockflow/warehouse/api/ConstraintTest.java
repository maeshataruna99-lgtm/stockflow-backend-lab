package dev.stockflow.warehouse.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.stockflow.warehouse.AbstractWarehouseIntegrationTest;
import dev.stockflow.warehouse.api.dto.StockLevelRequest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Constraint lab on the real PostgreSQL container: unique and foreign key constraints against concurrent requests.
 * A pre-check in the application ("does this code exist?") only gives a friendly answer; under concurrency the
 * constraint is what keeps the data correct, and what the API says about a violation must match the violation.
 */
@AutoConfigureMockMvc
class ConstraintTest extends AbstractWarehouseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private StockService stockService;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private static String sqlState(Throwable failure) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(failure);
        return root instanceof SQLException sql ? sql.getSQLState() : null;
    }

    @Test
    @DisplayName("12 simultaneous creates of one warehouse code: exactly one 201, the rest 409, no 500, one row")
    void concurrentCreatesOfOneCodeLeaveOneRow() throws Exception {
        String code = "RACE-" + System.nanoTime();
        int requests = 12;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<int[]>> futures = new ArrayList<>();
            List<String> details = java.util.Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    var response = mockMvc.perform(post("/api/v1/warehouses").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + code + "\",\"name\":\"race\",\"address\":\"x\"}"))
                            .andReturn().getResponse();
                    if (response.getStatus() == 409) {
                        details.add(response.getContentAsString());
                    }
                    return new int[] {response.getStatus()};
                }));
            }
            go.countDown();
            int created = 0;
            int conflicts = 0;
            for (Future<int[]> future : futures) {
                int status = future.get(60, TimeUnit.SECONDS)[0];
                if (status == 201) {
                    created++;
                } else if (status == 409) {
                    conflicts++;
                } else {
                    throw new AssertionError("unexpected status " + status);
                }
            }
            long fromConstraint = details.stream().filter(d -> d.contains("violates")).count();
            System.out.println("same warehouse code x" + requests + ": " + created + " x 201, " + conflicts
                    + " x 409 (" + fromConstraint + " of them from the unique constraint, "
                    + (conflicts - fromConstraint) + " from the existsByCode pre-check)");
            assertThat(created).isEqualTo(1);
            assertThat(conflicts).isEqualTo(requests - 1);
            assertThat(jdbc().queryForObject("SELECT count(*) FROM warehouses WHERE code = ?", Integer.class, code))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Eight threads each check "is this sku taken?", wait for each other, then insert: the classic check-then-act. */
    private int[] checkThenInsert(String table, String sku) throws Exception {
        int threads = 8;
        CyclicBarrier allHaveChecked = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try (Connection c = dataSource.getConnection()) {
                        int taken;
                        try (PreparedStatement s = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE sku = ?")) {
                            s.setString(1, sku);
                            var r = s.executeQuery();
                            r.next();
                            taken = r.getInt(1);
                        }
                        allHaveChecked.await(30, TimeUnit.SECONDS);
                        if (taken > 0) {
                            return "taken";
                        }
                        try (PreparedStatement s = c.prepareStatement("INSERT INTO " + table + " (sku) VALUES (?)")) {
                            s.setString(1, sku);
                            s.executeUpdate();
                            return "inserted";
                        } catch (SQLException e) {
                            return e.getSQLState();
                        }
                    }
                }));
            }
            int inserted = 0;
            int uniqueViolations = 0;
            for (Future<String> future : futures) {
                String outcome = future.get(60, TimeUnit.SECONDS);
                if ("inserted".equals(outcome)) {
                    inserted++;
                } else if ("23505".equals(outcome)) {
                    uniqueViolations++;
                }
            }
            return new int[] {inserted, uniqueViolations,
                    jdbc().queryForObject("SELECT count(*) FROM " + table + " WHERE sku = ?", Integer.class, sku)};
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Check-then-insert alone creates duplicates; the same code with a unique constraint keeps one row")
    void theConstraintIsWhatPreventsDuplicates() throws Exception {
        jdbc().execute("CREATE TABLE IF NOT EXISTS lab_without_unique (id BIGINT GENERATED ALWAYS AS IDENTITY, sku TEXT NOT NULL)");
        jdbc().execute("CREATE TABLE IF NOT EXISTS lab_with_unique (id BIGINT GENERATED ALWAYS AS IDENTITY, sku TEXT NOT NULL UNIQUE)");
        String sku = "SKU-" + System.nanoTime();

        int[] without = checkThenInsert("lab_without_unique", sku);
        int[] with = checkThenInsert("lab_with_unique", sku);

        System.out.println("check-then-insert, 8 threads: without a constraint " + without[0] + " inserted, rows "
                + without[2] + "; with UNIQUE " + with[0] + " inserted, " + with[1] + " refused with 23505, rows " + with[2]);
        assertThat(without[2]).as("rows without a constraint").isEqualTo(8);
        assertThat(with[0]).isEqualTo(1);
        assertThat(with[1]).isEqualTo(7);
        assertThat(with[2]).as("rows with a constraint").isEqualTo(1);
    }

    private long newLocation() {
        return jdbc().queryForObject("INSERT INTO storage_locations (warehouse_id, code) VALUES (1, ?) RETURNING id",
                Long.class, "FK-" + System.nanoTime());
    }

    @Test
    @DisplayName("A location that still has stock cannot be deleted: SQLSTATE 23503, the row stays")
    void deletingAReferencedLocationIsRefused() {
        long location = newLocation();
        stockService.create(new StockLevelRequest(location, System.nanoTime(), 3));

        assertThatThrownBy(() -> jdbc().update("DELETE FROM storage_locations WHERE id = ?", location))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"));

        assertThat(jdbc().queryForObject("SELECT count(*) FROM storage_locations WHERE id = ?", Integer.class, location))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("A delete of the parent waits for an uncommitted child insert, then fails: no orphan can appear")
    void parentDeleteWaitsForTheChildInsert() throws Exception {
        long location = newLocation();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection child = dataSource.getConnection()) {
            child.setAutoCommit(false);
            try (PreparedStatement insert = child.prepareStatement(
                    "INSERT INTO stock_levels (location_id, product_id, quantity) VALUES (?, ?, 1)")) {
                insert.setLong(1, location);
                insert.setLong(2, System.nanoTime());
                insert.executeUpdate(); // not committed yet: holds a key-share lock on the location row
            }

            Future<?> delete = pool.submit(() -> jdbc().update("DELETE FROM storage_locations WHERE id = ?", location));
            Thread.sleep(500);
            Integer waiting = jdbc().queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                            + "AND query LIKE 'DELETE FROM storage_locations%'", Integer.class);
            System.out.println("DELETE of the parent while the child insert is uncommitted: done=" + delete.isDone()
                    + ", backends waiting on a lock=" + waiting);
            assertThat(delete.isDone()).as("the delete is blocked").isFalse();
            assertThat(waiting).isEqualTo(1);

            child.commit();
            assertThatThrownBy(() -> delete.get(30, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(e -> assertThat(sqlState(e.getCause())).isEqualTo("23503"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc().queryForObject("SELECT count(*) FROM stock_levels WHERE location_id = ?", Integer.class, location))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("The API answers a foreign key violation as a conflict about a reference, not as a duplicate")
    void foreignKeyViolationIsNotReportedAsAUniqueViolation() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ProblemDetail foreignKey = handler.handleDataIntegrity(
                new DataIntegrityViolationException("fk", new SQLException("violates foreign key", "23503")));
        ProblemDetail unique = handler.handleDataIntegrity(
                new DataIntegrityViolationException("uq", new SQLException("violates unique", "23505")));

        assertThat(foreignKey.getStatus()).isEqualTo(409);
        assertThat(foreignKey.getTitle()).isEqualTo("Reference conflict");
        assertThat(foreignKey.getDetail()).doesNotContainIgnoringCase("unique");
        assertThat(unique.getStatus()).isEqualTo(409);
        assertThat(unique.getTitle()).isEqualTo("Duplicate resource");
        assertThat(unique.getDetail()).containsIgnoringCase("unique");

        ProblemDetail other = handler.handleDataIntegrity(
                new DataIntegrityViolationException("ck", new SQLException("violates check", "23514")));
        assertThat(other.getTitle()).isEqualTo("Constraint violation");
        assertThat(other.getDetail()).doesNotContainIgnoringCase("unique");
    }
}
