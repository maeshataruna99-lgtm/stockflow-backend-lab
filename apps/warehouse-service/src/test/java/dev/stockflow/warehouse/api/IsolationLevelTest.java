package dev.stockflow.warehouse.api;

import static java.sql.Connection.TRANSACTION_READ_COMMITTED;
import static java.sql.Connection.TRANSACTION_READ_UNCOMMITTED;
import static java.sql.Connection.TRANSACTION_REPEATABLE_READ;
import static java.sql.Connection.TRANSACTION_SERIALIZABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stockflow.warehouse.AbstractWarehouseIntegrationTest;
import dev.stockflow.warehouse.api.dto.StockLevelRequest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Isolation level lab on the real PostgreSQL container. Two JDBC connections are driven by one thread in a fixed
 * order, so every interleaving is deterministic: "T1" is the transaction under test, "T2" a second connection that
 * changes data in between. What T1 sees or is allowed to do depends on its isolation level.
 */
class IsolationLevelTest extends AbstractWarehouseIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private StockService stockService;

    private long newStock(long locationId, long productId, int quantity) {
        return stockService.create(new StockLevelRequest(locationId, productId, quantity)).id();
    }

    private Connection open(int isolation) throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(isolation);
        return connection;
    }

    private static int quantity(Connection c, long id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT quantity FROM stock_levels WHERE id = ?")) {
            s.setLong(1, id);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        }
    }

    private static void setQuantity(Connection c, long id, int quantity) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE stock_levels SET quantity = ? WHERE id = ?")) {
            s.setInt(1, quantity);
            s.setLong(2, id);
            s.executeUpdate();
        }
    }

    private static int rowsOfProduct(Connection c, long productId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT count(*) FROM stock_levels WHERE product_id = ?")) {
            s.setLong(1, productId);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        }
    }

    private static int sumOfProduct(Connection c, long productId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT sum(quantity) FROM stock_levels WHERE product_id = ?")) {
            s.setLong(1, productId);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        }
    }

    /** T2: change one quantity and commit, as a separate transaction. */
    private void committedUpdateByOther(long id, int quantity) throws SQLException {
        try (Connection t2 = open(TRANSACTION_READ_COMMITTED)) {
            setQuantity(t2, id, quantity);
            t2.commit();
        }
    }

    @Test
    @DisplayName("No dirty read: even READ UNCOMMITTED never shows another transaction's uncommitted change")
    void dirtyReadIsImpossibleInPostgreSql() throws Exception {
        long id = newStock(1, System.nanoTime(), 10);
        try (Connection t1 = open(TRANSACTION_READ_UNCOMMITTED); Connection t2 = open(TRANSACTION_READ_COMMITTED)) {
            setQuantity(t2, id, 99); // not committed

            assertThat(quantity(t1, id)).isEqualTo(10);

            t2.rollback();
        }
    }

    @Test
    @DisplayName("READ COMMITTED: reading the same row twice can give two answers (non-repeatable read)")
    void nonRepeatableReadAtReadCommitted() throws Exception {
        long id = newStock(1, System.nanoTime(), 10);
        try (Connection t1 = open(TRANSACTION_READ_COMMITTED)) {
            assertThat(quantity(t1, id)).isEqualTo(10);
            committedUpdateByOther(id, 7);

            assertThat(quantity(t1, id)).isEqualTo(7);
            t1.commit();
        }
    }

    @Test
    @DisplayName("REPEATABLE READ: the second read returns the snapshot of the first")
    void repeatableReadKeepsTheSnapshot() throws Exception {
        long id = newStock(1, System.nanoTime(), 10);
        try (Connection t1 = open(TRANSACTION_REPEATABLE_READ)) {
            assertThat(quantity(t1, id)).isEqualTo(10);
            committedUpdateByOther(id, 7);

            assertThat(quantity(t1, id)).isEqualTo(10);
            t1.commit();
        }
        try (Connection after = open(TRANSACTION_READ_COMMITTED)) {
            assertThat(quantity(after, id)).isEqualTo(7);
        }
    }

    @Test
    @DisplayName("READ COMMITTED: a row inserted and committed meanwhile shows up in the second count (phantom read)")
    void phantomReadAtReadCommitted() throws Exception {
        long product = System.nanoTime();
        newStock(1, product, 5);
        newStock(2, product, 5);
        try (Connection t1 = open(TRANSACTION_READ_COMMITTED)) {
            assertThat(rowsOfProduct(t1, product)).isEqualTo(2);
            newStock(3, product, 5);

            assertThat(rowsOfProduct(t1, product)).isEqualTo(3);
            t1.commit();
        }
    }

    @Test
    @DisplayName("REPEATABLE READ in PostgreSQL also hides rows inserted meanwhile (no phantom read)")
    void noPhantomReadAtRepeatableRead() throws Exception {
        long product = System.nanoTime();
        newStock(1, product, 5);
        newStock(2, product, 5);
        try (Connection t1 = open(TRANSACTION_REPEATABLE_READ)) {
            assertThat(rowsOfProduct(t1, product)).isEqualTo(2);
            newStock(3, product, 5);

            assertThat(rowsOfProduct(t1, product)).isEqualTo(2);
            t1.commit();
        }
    }

    @Test
    @DisplayName("READ COMMITTED: a write based on a stale read silently overwrites the other change (lost update)")
    void lostUpdateAtReadCommitted() throws Exception {
        long id = newStock(1, System.nanoTime(), 10);
        try (Connection t1 = open(TRANSACTION_READ_COMMITTED)) {
            int seen = quantity(t1, id); // 10
            committedUpdateByOther(id, 7); // another withdrawal of 3

            setQuantity(t1, id, seen - 3); // T1 also withdraws 3, from the stale value
            t1.commit();
        }
        try (Connection after = open(TRANSACTION_READ_COMMITTED)) {
            assertThat(quantity(after, id)).as("two withdrawals of 3 from 10 should leave 4").isEqualTo(7);
        }
    }

    @Test
    @DisplayName("REPEATABLE READ: the same stale write is rejected with SQLSTATE 40001 and nothing is lost")
    void lostUpdateIsRejectedAtRepeatableRead() throws Exception {
        long id = newStock(1, System.nanoTime(), 10);
        try (Connection t1 = open(TRANSACTION_REPEATABLE_READ)) {
            int seen = quantity(t1, id);
            committedUpdateByOther(id, 7);

            assertThatThrownBy(() -> setQuantity(t1, id, seen - 3))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("40001"));
            t1.rollback();
        }
        try (Connection after = open(TRANSACTION_READ_COMMITTED)) {
            assertThat(quantity(after, id)).isEqualTo(7);
        }
    }

    /**
     * Rule across two rows that no CHECK constraint can express: the product must keep at least 1 unit in total.
     * Each transaction reads the total (2), sees it may take one unit, and takes it from a different row.
     */
    private record Skew(Throwable secondFailure, int totalAfter) {
    }

    private Skew writeSkew(int isolation) throws Exception {
        long product = System.nanoTime();
        long a = newStock(1, product, 1);
        long b = newStock(2, product, 1);
        Throwable secondFailure = null;
        try (Connection t1 = open(isolation); Connection t2 = open(isolation)) {
            assertThat(sumOfProduct(t1, product)).isEqualTo(2);
            assertThat(sumOfProduct(t2, product)).isEqualTo(2);
            setQuantity(t1, a, 0);
            t1.commit();
            try {
                setQuantity(t2, b, 0);
                t2.commit();
            } catch (SQLException e) {
                secondFailure = e;
                System.out.println("write skew rejected: " + e.getMessage());
                t2.rollback();
            }
        }
        try (Connection after = open(TRANSACTION_READ_COMMITTED)) {
            return new Skew(secondFailure, sumOfProduct(after, product));
        }
    }

    @Test
    @DisplayName("REPEATABLE READ allows write skew: both transactions commit and the cross-row rule is broken")
    void writeSkewAtRepeatableRead() throws Exception {
        Skew skew = writeSkew(TRANSACTION_REPEATABLE_READ);

        assertThat(skew.secondFailure()).isNull();
        assertThat(skew.totalAfter()).as("the rule was: at least 1 unit left").isZero();
    }

    @Test
    @DisplayName("SERIALIZABLE rejects write skew with SQLSTATE 40001 and the cross-row rule stays intact")
    void serializableRejectsWriteSkew() throws Exception {
        Skew skew = writeSkew(TRANSACTION_SERIALIZABLE);

        assertThat(skew.secondFailure()).isInstanceOfSatisfying(SQLException.class,
                e -> assertThat(e.getSQLState()).isEqualTo("40001"));
        assertThat(skew.totalAfter()).as("only the first transaction's unit was taken").isEqualTo(1);
    }
}
