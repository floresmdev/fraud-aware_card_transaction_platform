package dev.floresm.transactions.domain.account;

import dev.floresm.transactions.domain.money.Money;

import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * A credit card account: the thing that owns a credit limit. Cards are issued against an account,
 * and every approved transaction "reserves" part of the account's available credit.
 *
 * <p><b>Immutable entity.</b> Unlike {@link Money}, an Account has an identity ({@code id}): two
 * accounts with the same numbers are still different accounts. But we still never mutate it.
 * {@link #reserve(Money)} returns a new Account with the updated balance, and the repository
 * persists that new state. Benefits: thread safety for free, no "who changed this object?" bugs,
 * and every state transition goes through one method that enforces the rules.
 *
 * <p><b>Invariants</b> (rules that must always hold, checked in the compact constructor):
 * <ul>
 *   <li>credit limit and available credit share the account currency</li>
 *   <li>0 &lt;= available credit &lt;= credit limit</li>
 * </ul>
 * The database repeats these as CHECK constraints (V1 migration): defense in depth.
 */
public record Account(
        UUID id,
        String holderName,
        Money creditLimit,
        Money availableCredit,
        Instant createdAt
) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(holderName, "holderName");
        Objects.requireNonNull(creditLimit, "creditLimit");
        Objects.requireNonNull(availableCredit, "availableCredit");
        Objects.requireNonNull(createdAt, "createdAt");
        if (holderName.isBlank()) {
            throw new IllegalArgumentException("holderName must not be blank");
        }
        if (!creditLimit.hasSameCurrencyAs(availableCredit)) {
            throw new IllegalArgumentException("creditLimit and availableCredit currencies differ");
        }
        if (creditLimit.isNegative()) {
            throw new IllegalArgumentException("creditLimit must not be negative");
        }
        if (availableCredit.isNegative() || availableCredit.isGreaterThan(creditLimit)) {
            throw new IllegalArgumentException("availableCredit must be between 0 and creditLimit");
        }
    }

    /** Factory for a brand-new account: nothing spent yet, so available credit equals the limit. */
    public static Account open(String holderName, Money creditLimit, Instant now) {
        return new Account(UUID.randomUUID(), holderName, creditLimit, creditLimit, now);
    }

    public Currency currency() {
        return creditLimit.currency();
    }

    public boolean canCover(Money amount) {
        return availableCredit.hasSameCurrencyAs(amount) && !amount.isGreaterThan(availableCredit);
    }

    /**
     * Places a hold for an authorized amount. In card networks, authorization does not move money
     * yet (that happens later at "settlement/capture"); it reduces what the customer can still spend.
     *
     * @throws IllegalStateException if the amount exceeds available credit. Callers are expected to
     *                               check with an authorization rule first; this is a last guard.
     */
    public Account reserve(Money amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("reserved amount must be positive");
        }
        if (!canCover(amount)) {
            throw new IllegalStateException("insufficient available credit");
        }
        return new Account(id, holderName, creditLimit, availableCredit.minus(amount), createdAt);
    }
}
