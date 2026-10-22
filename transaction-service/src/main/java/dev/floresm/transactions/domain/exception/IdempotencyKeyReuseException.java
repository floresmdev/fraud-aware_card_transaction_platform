package dev.floresm.transactions.domain.exception;

/**
 * The client sent an Idempotency-Key that was already used for a <b>different</b> request. This is a
 * client bug, and silently returning the old result would hide it. Mapped to HTTP 422.
 */
public class IdempotencyKeyReuseException extends RuntimeException {

    public IdempotencyKeyReuseException(String key) {
        super("Idempotency-Key '%s' was already used with a different request body".formatted(key));
    }
}
