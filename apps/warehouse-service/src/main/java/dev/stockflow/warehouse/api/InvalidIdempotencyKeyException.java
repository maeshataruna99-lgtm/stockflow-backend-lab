package dev.stockflow.warehouse.api;

/** The Idempotency-Key header is not 8 to 80 characters of letters, digits and _ . : -; mapped to a 400 problem+json. */
public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException(String message) {
        super(message);
    }
}
