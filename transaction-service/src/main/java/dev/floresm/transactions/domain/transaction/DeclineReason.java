package dev.floresm.transactions.domain.transaction;

/**
 * Machine-readable reason for a decline. Clients branch on these codes (e.g. show "card expired"),
 * so they are part of the public API contract: add new values, never rename existing ones.
 */
public enum DeclineReason {
    CARD_NOT_ACTIVE,
    CARD_EXPIRED,
    CURRENCY_MISMATCH,
    INSUFFICIENT_CREDIT,
    SUSPECTED_FRAUD
}
