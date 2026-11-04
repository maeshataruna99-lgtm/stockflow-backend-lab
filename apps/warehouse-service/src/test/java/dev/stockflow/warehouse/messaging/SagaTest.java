package dev.stockflow.warehouse.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Orchestrated Saga with explicit state transitions, compensating actions, and crash recovery (Phase 9, unit 9.4:
 * topic 43).
 *
 * <p>Models an order fulfillment flow across local tables representing two separate participants (inventory reservation
 * and payment capture) without a distributed 2PC lock. Every step and compensation is idempotent and persisted in
 * {@code saga_instances}, so a crashed saga can be resumed by a recovery worker without double-reserving or
 * double-refunding stock.
 */
class SagaTest {

    enum SagaState {
        STARTED,
        STOCK_RESERVED,
        COMPLETED,
        COMPENSATING,
        COMPENSATED
    }

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;

    @BeforeAll
    static void startDatabase() {
        postgres = new PostgreSQLContainer<>("postgres:16.15-alpine");
        postgres.start();
        SingleConnectionDataSource ds = new SingleConnectionDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), true);
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        jdbc.execute("CREATE TABLE saga_stock (stock_id BIGINT PRIMARY KEY, available INT NOT NULL CHECK (available >= 0))");
        jdbc.execute("CREATE TABLE saga_reservations ("
                + "saga_id VARCHAR(64) PRIMARY KEY, "
                + "stock_id BIGINT NOT NULL, "
                + "quantity INT NOT NULL, "
                + "status VARCHAR(20) NOT NULL)");
        jdbc.execute("CREATE TABLE saga_payments ("
                + "saga_id VARCHAR(64) PRIMARY KEY, "
                + "amount_cents INT NOT NULL)");
        jdbc.execute("CREATE TABLE saga_instances ("
                + "saga_id VARCHAR(64) PRIMARY KEY, "
                + "stock_id BIGINT NOT NULL, "
                + "quantity INT NOT NULL, "
                + "amount_cents INT NOT NULL, "
                + "state VARCHAR(32) NOT NULL, "
                + "failure_reason TEXT)");
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    static String startSaga(long stockId, int quantity, int amountCents) {
        String sagaId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO saga_instances (saga_id, stock_id, quantity, amount_cents, state) VALUES (?, ?, ?, ?, ?)",
                sagaId, stockId, quantity, amountCents, SagaState.STARTED.name());
        return sagaId;
    }

    static void reserveStockStep(String sagaId) {
        tx.executeWithoutResult(status -> {
            int inserted = jdbc.update(
                    "INSERT INTO saga_reservations (saga_id, stock_id, quantity, status) "
                            + "SELECT saga_id, stock_id, quantity, 'RESERVED' FROM saga_instances WHERE saga_id = ? "
                            + "ON CONFLICT (saga_id) DO NOTHING",
                    sagaId);
            if (inserted == 1) {
                jdbc.update("UPDATE saga_stock s SET available = s.available - i.quantity "
                        + "FROM saga_instances i WHERE i.saga_id = ? AND s.stock_id = i.stock_id", sagaId);
            }
            jdbc.update("UPDATE saga_instances SET state = ? WHERE saga_id = ?",
                    SagaState.STOCK_RESERVED.name(), sagaId);
        });
    }

    static void chargePaymentStep(String sagaId, boolean paymentDeclined) {
        if (paymentDeclined) {
            jdbc.update("UPDATE saga_instances SET state = ?, failure_reason = ? WHERE saga_id = ?",
                    SagaState.COMPENSATING.name(), "Payment card declined", sagaId);
            return;
        }
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO saga_payments (saga_id, amount_cents) "
                    + "SELECT saga_id, amount_cents FROM saga_instances WHERE saga_id = ? "
                    + "ON CONFLICT (saga_id) DO NOTHING", sagaId);
            jdbc.update("UPDATE saga_instances SET state = ? WHERE saga_id = ?",
                    SagaState.COMPLETED.name(), sagaId);
        });
    }

    /**
     * Idempotent compensation: releases the stock reservation only if its status is still {@code RESERVED}, then marks
     * the saga {@code COMPENSATED}. Running this twice never adds the units back a second time.
     */
    static void compensateStockStep(String sagaId) {
        tx.executeWithoutResult(status -> {
            int released = jdbc.update(
                    "UPDATE saga_reservations SET status = 'RELEASED' WHERE saga_id = ? AND status = 'RESERVED'",
                    sagaId);
            if (released == 1) {
                jdbc.update("UPDATE saga_stock s SET available = s.available + r.quantity "
                        + "FROM saga_reservations r WHERE r.saga_id = ? AND s.stock_id = r.stock_id", sagaId);
            }
            jdbc.update("UPDATE saga_instances SET state = ? WHERE saga_id = ?",
                    SagaState.COMPENSATED.name(), sagaId);
        });
    }

    static SagaState executeOrderSaga(long stockId, int quantity, int amountCents, boolean paymentDeclined, boolean crashAfterReserve) {
        String sagaId = startSaga(stockId, quantity, amountCents);
        reserveStockStep(sagaId);
        if (crashAfterReserve) {
            throw new IllegalStateException("Orchestrator crashed after STOCK_RESERVED for " + sagaId);
        }
        chargePaymentStep(sagaId, paymentDeclined);
        if (stateOf(sagaId) == SagaState.COMPENSATING) {
            compensateStockStep(sagaId);
        }
        return stateOf(sagaId);
    }

    /** Recovery worker: finds sagas stuck in non-terminal states and drives them to a safe compensated state. */
    static int recoverIncompleteSagas() {
        List<String> stuck = jdbc.queryForList(
                "SELECT saga_id FROM saga_instances WHERE state IN ('STARTED', 'STOCK_RESERVED', 'COMPENSATING') ORDER BY saga_id",
                String.class);
        for (String sagaId : stuck) {
            jdbc.update("UPDATE saga_instances SET state = ?, failure_reason = coalesce(failure_reason, 'Recovered after timeout') WHERE saga_id = ?",
                    SagaState.COMPENSATING.name(), sagaId);
            compensateStockStep(sagaId);
        }
        return stuck.size();
    }

    private static SagaState stateOf(String sagaId) {
        String raw = jdbc.queryForObject("SELECT state FROM saga_instances WHERE saga_id = ?", String.class, sagaId);
        return SagaState.valueOf(raw);
    }

    private static int available(long stockId) {
        return jdbc.queryForObject("SELECT available FROM saga_stock WHERE stock_id = ?", Integer.class, stockId);
    }

    @Test
    @DisplayName("Happy path: reserves stock, captures payment, and reaches COMPLETED")
    void happyPathReservesStockChargesPaymentAndCompletes() {
        jdbc.update("INSERT INTO saga_stock (stock_id, available) VALUES (101, 10)");

        SagaState finalState = executeOrderSaga(101L, 4, 4_000, false, false);

        assertThat(finalState).isEqualTo(SagaState.COMPLETED);
        assertThat(available(101L)).isEqualTo(6);
    }

    @Test
    @DisplayName("Payment failure transitions through COMPENSATING to COMPENSATED and restores the reserved stock")
    void paymentFailureTriggersCompensationAndRestoresStock() {
        jdbc.update("INSERT INTO saga_stock (stock_id, available) VALUES (102, 10)");

        SagaState finalState = executeOrderSaga(102L, 4, 4_000, true, false);

        assertThat(finalState).isEqualTo(SagaState.COMPENSATED);
        assertThat(available(102L)).isEqualTo(10);
    }

    @Test
    @DisplayName("A saga that crashes in STOCK_RESERVED is recovered to COMPENSATED, and repeating compensation is idempotent")
    void crashAfterReservationIsRecoveredAndIdempotentlyCompensated() {
        jdbc.update("INSERT INTO saga_stock (stock_id, available) VALUES (103, 10)");
        String sagaId = startSaga(103L, 4, 4_000);
        reserveStockStep(sagaId);

        // Simulate crash right after reservation: stock is 6 and state is STOCK_RESERVED
        assertThat(stateOf(sagaId)).isEqualTo(SagaState.STOCK_RESERVED);
        assertThat(available(103L)).isEqualTo(6);

        // Recovery worker detects the incomplete saga and compensates it
        int recovered = recoverIncompleteSagas();
        assertThat(recovered).isEqualTo(1);
        assertThat(stateOf(sagaId)).isEqualTo(SagaState.COMPENSATED);
        assertThat(available(103L)).isEqualTo(10);

        // Running compensation or recovery again does not double-refund stock to 14
        compensateStockStep(sagaId);
        assertThat(recoverIncompleteSagas()).isZero();
        assertThat(available(103L)).isEqualTo(10);
    }
}
