package dev.stockflow.warehouse.api;

import dev.stockflow.warehouse.api.dto.StockLevelRequest;
import dev.stockflow.warehouse.api.dto.StockLevelResponse;
import dev.stockflow.warehouse.api.dto.StockTransferRequest;
import dev.stockflow.warehouse.api.dto.StockTransferResponse;
import dev.stockflow.warehouse.api.dto.StockWithdrawRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

/** Stock per location and product (Phase 7). Not routed by the gateway; services are reached directly in the labs. */
@Tag(name = "Stock", description = "Stock levels per storage location and product (warehouse-service)")
@RestController
@RequestMapping("/api/v1/stock")
public class StockController {

    private final StockService stockService;
    private final IdempotencyService idempotency;

    StockController(StockService stockService, IdempotencyService idempotency) {
        this.stockService = stockService;
        this.idempotency = idempotency;
    }

    @Operation(summary = "Create a stock row")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; Location header holds the new resource URI"),
            @ApiResponse(responseCode = "400", description = "Bean validation failed (application/problem+json)"),
            @ApiResponse(responseCode = "404", description = "No such location (application/problem+json)"),
            @ApiResponse(responseCode = "409", description = "Stock for this product and location exists (application/problem+json)")
    })
    @PostMapping
    ResponseEntity<StockLevelResponse> create(@Valid @RequestBody StockLevelRequest request) {
        StockLevelResponse created = stockService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/stock/" + created.id())).body(created);
    }

    @Operation(summary = "Get one stock row")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The stock row"),
            @ApiResponse(responseCode = "404", description = "No stock with this id (application/problem+json)")
    })
    @GetMapping("/{id}")
    StockLevelResponse get(@PathVariable Long id) {
        return stockService.get(id);
    }

    @Operation(summary = "Transfer units between two stock rows",
            description = "Locks both rows in ascending id order, so opposite transfers cannot deadlock.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Both stock rows after the transfer"),
            @ApiResponse(responseCode = "400", description = "Quantity below 1 or the same row twice (application/problem+json)"),
            @ApiResponse(responseCode = "404", description = "A stock row does not exist (application/problem+json)"),
            @ApiResponse(responseCode = "409", description = "Not enough stock in the source row (application/problem+json)")
    })
    @PostMapping("/transfer")
    ResponseEntity<StockTransferResponse> transfer(
            @Valid @RequestBody StockTransferRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey == null) {
            return ResponseEntity.ok(stockService.transfer(request.fromId(), request.toId(), request.quantity()));
        }
        return answer(idempotency.execute(idempotencyKey, "transfer",
                Map.of("fromId", request.fromId(), "toId", request.toId(), "quantity", request.quantity()),
                StockTransferResponse.class,
                () -> stockService.transfer(request.fromId(), request.toId(), request.quantity())));
    }

    @Operation(summary = "Withdraw units from a stock row",
            description = "Never takes more than is in stock, also under concurrent requests.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The stock row after the withdrawal"),
            @ApiResponse(responseCode = "400", description = "Quantity below 1 (application/problem+json)"),
            @ApiResponse(responseCode = "404", description = "No stock with this id (application/problem+json)"),
            @ApiResponse(responseCode = "409", description = "Not enough stock (application/problem+json)")
    })
    @PostMapping("/{id}/withdraw")
    ResponseEntity<StockLevelResponse> withdraw(
            @PathVariable Long id,
            @Valid @RequestBody StockWithdrawRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey == null) {
            return ResponseEntity.ok(stockService.withdraw(id, request.quantity()));
        }
        return answer(idempotency.execute(idempotencyKey, "withdraw",
                Map.of("id", id, "quantity", request.quantity()), StockLevelResponse.class,
                () -> stockService.withdraw(id, request.quantity())));
    }

    /** 200 with the body; a stored answer of an earlier request with the same Idempotency-Key is marked as replayed. */
    private static <T> ResponseEntity<T> answer(IdempotencyService.Outcome<T> outcome) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }
}
