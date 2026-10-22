package dev.floresm.transactions.domain.fraud;

/**
 * Port to "whatever decides if a transaction looks fraudulent".
 *
 * <p>Phase 1 wires a placeholder (infrastructure/fraud/ApproveAllFraudScreener.java). Phase 3 adds
 * an HTTP adapter that calls the Python scoring service with a timeout and a fallback. Because
 * AuthorizationService only knows this interface, that swap requires <b>zero</b> changes to the
 * authorization logic. That is the payoff of coding to an interface.
 *
 * <p>Contract: implementations may perform network I/O and may be slow, so callers must never
 * invoke this while holding a database lock.
 */
public interface FraudScreener {

    FraudAssessment screen(FraudScreeningRequest request);
}
