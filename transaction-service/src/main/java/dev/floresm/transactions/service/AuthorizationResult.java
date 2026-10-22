package dev.floresm.transactions.service;

import dev.floresm.transactions.domain.transaction.Transaction;

/**
 * @param replayed true when this is a stored result returned for a retried request with an
 *                 already-used Idempotency-Key. The controller exposes it as a response header.
 */
public record AuthorizationResult(Transaction transaction, boolean replayed) {
}
