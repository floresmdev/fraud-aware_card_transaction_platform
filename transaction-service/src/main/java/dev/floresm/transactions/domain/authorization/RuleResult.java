package dev.floresm.transactions.domain.authorization;

import dev.floresm.transactions.domain.transaction.DeclineReason;

import java.util.Objects;

/**
 * The result of one {@link AuthorizationRule}: either it passed, or it failed with a reason.
 *
 * <p><b>Sealed interface (Java 17+).</b> {@code permits} lists every allowed implementation, so the
 * compiler knows the hierarchy is closed. A {@code switch} over a RuleResult with one case per
 * permitted type needs no {@code default}, and if someone adds a third type the compiler flags
 * every switch that forgot it. Think of it as an enum whose values can carry different data.
 * Functional languages call this an "algebraic data type" / "sum type".
 *
 * <p>Why not return {@code boolean}? A boolean cannot carry the decline reason. Why not throw an
 * exception? A decline is a normal business outcome, not an error. Exceptions are for the
 * unexpected, and using them for control flow makes code slow and hard to follow.
 */
public sealed interface RuleResult permits RuleResult.Pass, RuleResult.Fail {

    record Pass() implements RuleResult {
    }

    record Fail(DeclineReason reason) implements RuleResult {
        public Fail {
            Objects.requireNonNull(reason, "reason");
        }
    }

    // Shared instance: Pass has no state, so one object is enough (like an enum constant).
    Pass PASS = new Pass();

    static RuleResult pass() {
        return PASS;
    }

    static RuleResult fail(DeclineReason reason) {
        return new Fail(reason);
    }
}
