package dev.stockflow.warehouse.api;

import dev.stockflow.warehouse.api.dto.StockLevelRequest;
import dev.stockflow.warehouse.api.dto.StockLevelResponse;
import dev.stockflow.warehouse.api.dto.StockTransferResponse;
import dev.stockflow.warehouse.domain.StockLevel;
import dev.stockflow.warehouse.domain.StorageLocation;
import dev.stockflow.warehouse.repository.StockLevelRepository;
import dev.stockflow.warehouse.repository.StorageLocationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Stock use cases: create a stock row, read it, withdraw from it. */
@Service
@EnableConfigurationProperties(StockProperties.class)
public class StockService {

    private final StockLevelRepository stockLevelRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final StockProperties properties;
    private final MeterRegistry meters;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transaction;

    StockService(StockLevelRepository stockLevelRepository, StorageLocationRepository storageLocationRepository,
                 StockProperties properties, MeterRegistry meters, JdbcTemplate jdbcTemplate,
                 PlatformTransactionManager transactionManager) {
        this.stockLevelRepository = stockLevelRepository;
        this.storageLocationRepository = storageLocationRepository;
        this.properties = properties;
        this.meters = meters;
        this.jdbcTemplate = jdbcTemplate;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public StockLevelResponse create(StockLevelRequest request) {
        StorageLocation location = storageLocationRepository.findById(request.locationId())
                .orElseThrow(() -> ResourceNotFoundException.of("location", request.locationId()));
        if (stockLevelRepository.existsByLocationIdAndProductId(request.locationId(), request.productId())) {
            throw new DuplicateResourceException("stock for product " + request.productId()
                    + " at location " + request.locationId() + " already exists");
        }
        return StockLevelResponse.from(stockLevelRepository.saveAndFlush(
                new StockLevel(location, request.productId(), request.quantity())));
    }

    @Transactional(readOnly = true)
    public StockLevelResponse get(Long id) {
        return stockLevelRepository.findById(id)
                .map(StockLevelResponse::from)
                .orElseThrow(() -> ResourceNotFoundException.of("stock", id));
    }

    @Transactional
    public StockLevelResponse withdraw(Long id, int quantity) {
        if (stockLevelRepository.withdraw(id, quantity) == 0) {
            StockLevel stock = stockLevelRepository.findById(id)
                    .orElseThrow(() -> ResourceNotFoundException.of("stock", id));
            throw new InsufficientStockException("stock " + id + " has " + stock.getQuantity()
                    + " units, " + quantity + " requested");
        }
        return get(id);
    }

    /**
     * Lab only (race condition lab, reachable only with stockflow.lab.unsafe-stock=true): read, check in Java,
     * write back the computed value. Two transactions that read the same quantity both pass the check and the
     * second write overwrites the first (lost update), so more units leave than were in stock.
     */
    @Transactional
    public StockLevelResponse withdrawUnsafe(Long id, int quantity) {
        StockLevel stock = stockLevelRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("stock", id));
        if (stock.getQuantity() < quantity) {
            throw new InsufficientStockException("stock " + id + " has " + stock.getQuantity()
                    + " units, " + quantity + " requested");
        }
        stock.setQuantity(stock.getQuantity() - quantity);
        return StockLevelResponse.from(stockLevelRepository.saveAndFlush(stock));
    }

    /**
     * Lab (locking lab): optimistic locking. Read the row and its version, compute in Java, write only if the version
     * is still the same. On a lost race re-read and try again, at most {@code optimisticAttempts} times, then give up
     * with a 409 that is safe to retry. Each attempt is its own transaction so it sees the committed row.
     */
    public StockLevelResponse withdrawOptimistic(Long id, int quantity) {
        for (int attempt = 1; attempt <= properties.optimisticAttempts(); attempt++) {
            if (attempt > 1) {
                meters.counter("warehouse.stock.retries", "lock", "optimistic").increment();
            }
            Boolean written = transaction.execute(status -> {
                StockLevel stock = stockLevelRepository.findById(id)
                        .orElseThrow(() -> ResourceNotFoundException.of("stock", id));
                if (stock.getQuantity() < quantity) {
                    throw new InsufficientStockException("stock " + id + " has " + stock.getQuantity()
                            + " units, " + quantity + " requested");
                }
                return stockLevelRepository.updateQuantityIfVersion(
                        id, stock.getQuantity() - quantity, stock.getVersion()) == 1;
            });
            if (Boolean.TRUE.equals(written)) {
                return get(id);
            }
        }
        throw new ConcurrentUpdateException("stock " + id + " changed concurrently "
                + properties.optimisticAttempts() + " times in a row, retry the request");
    }

    /**
     * Lab (locking lab): pessimistic locking. SELECT ... FOR UPDATE holds the row lock from the read to the commit,
     * so concurrent withdrawals queue up. The wait is bounded by lock_timeout (PostgreSQL setting, local to this
     * transaction); past it the call fails with a locking exception instead of hanging.
     */
    @Transactional
    public StockLevelResponse withdrawPessimistic(Long id, int quantity) {
        limitLockWait();
        StockLevel stock = stockLevelRepository.findByIdForUpdate(id)
                .orElseThrow(() -> ResourceNotFoundException.of("stock", id));
        if (stock.getQuantity() < quantity) {
            throw new InsufficientStockException("stock " + id + " has " + stock.getQuantity()
                    + " units, " + quantity + " requested");
        }
        stock.setQuantity(stock.getQuantity() - quantity);
        return StockLevelResponse.from(stockLevelRepository.saveAndFlush(stock));
    }

    /**
     * Moves units between two rows. Both rows are locked in one statement in ascending id order, whatever the
     * direction of the transfer, so two opposite transfers queue up instead of deadlocking.
     */
    @Transactional
    public StockTransferResponse transfer(Long fromId, Long toId, int quantity) {
        limitLockWait();
        Map<Long, StockLevel> rows = new HashMap<>();
        stockLevelRepository.findAllByIdInForUpdate(List.of(fromId, toId)).forEach(s -> rows.put(s.getId(), s));
        return move(fromId, rows.get(fromId), toId, rows.get(toId), quantity);
    }

    /**
     * Lab only (deadlock lab, behind stockflow.lab.unsafe-stock): locks "from" first and "to" second, with a pause
     * in between that makes the overlap certain. Two opposite transfers each hold one lock and wait for the other.
     * PostgreSQL detects it after deadlock_timeout, rolls one transaction back (SQLSTATE 40P01) and the other commits.
     */
    @Transactional
    public StockTransferResponse transferUnordered(Long fromId, Long toId, int quantity, long pauseMillis) {
        limitLockWait();
        StockLevel from = stockLevelRepository.findByIdForUpdate(fromId).orElse(null);
        if (pauseMillis > 0) {
            jdbcTemplate.queryForObject("SELECT pg_sleep(?)::text", String.class, pauseMillis / 1000.0);
        }
        StockLevel to = stockLevelRepository.findByIdForUpdate(toId).orElse(null);
        return move(fromId, from, toId, to, quantity);
    }

    /** Both rows are locked here; a null row does not exist (404). */
    private StockTransferResponse move(Long fromId, StockLevel from, Long toId, StockLevel to, int quantity) {
        if (from == null || to == null) {
            throw ResourceNotFoundException.of("stock", from == null ? fromId : toId);
        }
        if (from.getQuantity() < quantity) {
            throw new InsufficientStockException("stock " + from.getId() + " has " + from.getQuantity()
                    + " units, " + quantity + " requested");
        }
        stockLevelRepository.addQuantity(from.getId(), -quantity);
        stockLevelRepository.addQuantity(to.getId(), quantity);
        return new StockTransferResponse(get(from.getId()), get(to.getId()));
    }

    /** Bounds how long this transaction waits for a row lock (PostgreSQL setting, local to the transaction). */
    private void limitLockWait() {
        jdbcTemplate.queryForObject("SELECT set_config('lock_timeout', ?, true)", String.class,
                properties.lockTimeout().toMillis() + "ms");
    }
}
