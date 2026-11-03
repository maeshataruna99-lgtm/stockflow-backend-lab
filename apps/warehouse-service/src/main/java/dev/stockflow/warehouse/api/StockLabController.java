package dev.stockflow.warehouse.api;

import dev.stockflow.warehouse.api.dto.StockLevelResponse;
import dev.stockflow.warehouse.api.dto.StockWithdrawRequest;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The deliberately broken withdraw of the race condition lab and the two locking variants of the locking lab. They do
 * not exist unless
 * {@code stockflow.lab.unsafe-stock=true}; compose.labs.yaml sets it, the core compose file does not.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/stock")
@ConditionalOnProperty(name = "stockflow.lab.unsafe-stock", havingValue = "true")
public class StockLabController {

    private final StockService stockService;

    StockLabController(StockService stockService) {
        this.stockService = stockService;
    }

    @PostMapping("/{id}/withdraw-unsafe")
    StockLevelResponse withdrawUnsafe(@PathVariable Long id, @Valid @RequestBody StockWithdrawRequest request) {
        return stockService.withdrawUnsafe(id, request.quantity());
    }

    @PostMapping("/{id}/withdraw-optimistic")
    StockLevelResponse withdrawOptimistic(@PathVariable Long id, @Valid @RequestBody StockWithdrawRequest request) {
        return stockService.withdrawOptimistic(id, request.quantity());
    }

    @PostMapping("/{id}/withdraw-pessimistic")
    StockLevelResponse withdrawPessimistic(@PathVariable Long id, @Valid @RequestBody StockWithdrawRequest request) {
        return stockService.withdrawPessimistic(id, request.quantity());
    }
}
