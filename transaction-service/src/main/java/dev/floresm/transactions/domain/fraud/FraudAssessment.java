package dev.floresm.transactions.domain.fraud;

import java.util.List;
import java.util.Objects;

/**
 * The screener's verdict.
 *
 * @param score   estimated fraud probability in [0, 1]. A {@code double} is fine here: it is a
 *                probability, not money, and nobody reconciles it to the cent.
 * @param reasons human-readable explanations ("amount 8x above card average"). Explainability
 *                matters: analysts, customers and regulators all ask "why was this declined?"
 */
public record FraudAssessment(FraudDecision decision, double score, List<String> reasons) {

    public FraudAssessment {
        Objects.requireNonNull(decision, "decision");
        if (score < 0.0 || score > 1.0 || Double.isNaN(score)) {
            throw new IllegalArgumentException("score must be within [0, 1]");
        }
        // Defensive copy: List.copyOf returns an unmodifiable list, so the caller cannot mutate our state.
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
    }

    public static FraudAssessment approve(String reason) {
        return new FraudAssessment(FraudDecision.APPROVE, 0.0, List.of(reason));
    }
}
