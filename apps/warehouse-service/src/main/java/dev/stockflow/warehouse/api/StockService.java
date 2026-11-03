package dev.stockflow.warehouse.api;

import dev.stockflow.warehouse.api.dto.StockLevelRequest;
import dev.stockflow.warehouse.api.dto.StockLevelResponse;
import dev.stockflow.warehouse.domain.StockLevel;
import dev.stockflow.warehouse.domain.StorageLocation;
import dev.stockflow.warehouse.repository.StockLevelRepository;
import dev.stockflow.warehouse.repository.StorageLocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stock use cases: create a stock row, read it, withdraw from it. */
@Service
public class StockService {

    private final StockLevelRepository stockLevelRepository;
    private final StorageLocationRepository storageLocationRepository;

    StockService(StockLevelRepository stockLevelRepository, StorageLocationRepository storageLocationRepository) {
        this.stockLevelRepository = stockLevelRepository;
        this.storageLocationRepository = storageLocationRepository;
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
}
