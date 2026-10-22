package dev.floresm.transactions.domain.transaction;

/** Outcome of an authorization request. */
public enum TransactionStatus {
    /** Credit reserved; the merchant may complete the sale. */
    APPROVED,
    /** Rejected; nothing reserved. {@link Transaction#declineReason()} says why. */
    DECLINED,
    /**
     * The fraud screener was unsure. We reserve the credit (so the customer cannot overspend while
     * an analyst looks at it) and flag it. How REVIEW should behave at the point of sale is a
     * business decision that will be finalized in Phase 3 (docs/adr).
     */
    PENDING_REVIEW
}
