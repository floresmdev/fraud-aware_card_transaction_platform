package dev.floresm.transactions.domain.money;

/**
 * Thrown when a caller supplies an amount or currency that cannot be represented as {@link Money}
 * (too many decimals, unknown currency, overflow).
 *
 * <p>It is a separate type on purpose: the API layer maps <i>this</i> exception to HTTP 400 because
 * it means "the client sent bad input". A plain {@link IllegalArgumentException} (for example a
 * currency mismatch inside our own code) means "we have a bug" and must surface as HTTP 500.
 */
public class InvalidAmountException extends RuntimeException {

    public InvalidAmountException(String message) {
        super(message);
    }
}
