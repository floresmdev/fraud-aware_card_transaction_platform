package dev.floresm.transactions.domain.card;

/**
 * Lifecycle of a card. An {@code enum} gives us a closed, type-safe set of values: the compiler
 * rejects typos that a String ("ACTVE") would let through, and a {@code switch} over it can be
 * checked for exhaustiveness.
 */
public enum CardStatus {
    ACTIVE,
    /** Blocked by the customer or the bank (lost, stolen, suspected fraud). Rejects new authorizations. */
    BLOCKED
}
