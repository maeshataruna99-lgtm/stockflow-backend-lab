package dev.stockflow.warehouse.api;

/** An optimistic update kept losing the version race and gave up; mapped to a 409 problem+json, safe to retry. */
public class ConcurrentUpdateException extends RuntimeException {

    public ConcurrentUpdateException(String message) {
        super(message);
    }
}
