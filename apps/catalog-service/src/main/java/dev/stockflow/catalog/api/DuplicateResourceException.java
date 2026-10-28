package dev.stockflow.catalog.api;

/** A unique business key already exists; mapped to a 409 problem+json. */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
