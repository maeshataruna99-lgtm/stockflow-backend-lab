package dev.stockflow.warehouse.api;

/** An Idempotency-Key was sent again with a different caller, operation or request; mapped to a 422 problem+json. */
public class IdempotencyKeyReuseException extends RuntimeException {

    public IdempotencyKeyReuseException(String message) {
        super(message);
    }
}
