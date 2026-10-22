package dev.floresm.transactions.domain.fraud;

/** What the fraud screener recommends. Mirrors the Python service's response (Phase 2). */
public enum FraudDecision {
    APPROVE,
    REVIEW,
    DECLINE
}
