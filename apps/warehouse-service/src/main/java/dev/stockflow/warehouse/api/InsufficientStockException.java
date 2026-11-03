package dev.stockflow.warehouse.api;

/** A withdrawal asks for more than is in stock; mapped to a 409 problem+json. */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String message) {
        super(message);
    }
}
