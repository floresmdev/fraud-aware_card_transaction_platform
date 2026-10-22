package dev.floresm.transactions.domain.card;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * A payment card issued against an {@code Account}.
 *
 * <p><b>We never store the full card number (PAN).</b> Storing PANs pulls the whole system into
 * PCI DSS scope (strict, expensive security audits). Real issuers keep PANs in a separate, hardened
 * "vault" and the rest of the platform only sees a token (here: the card {@code id}) plus the last
 * four digits for display. Interview point: "minimize the blast radius of sensitive data".
 */
public record Card(
        UUID id,
        UUID accountId,
        String last4,
        YearMonth expiry,
        CardStatus status,
        Instant createdAt
) {

    /** Cards are valid for 4 years, a common issuer default. */
    static final int VALIDITY_YEARS = 4;

    // SecureRandom, not java.util.Random: card-related values must not be predictable.
    private static final RandomGenerator RANDOM = new SecureRandom();

    public Card {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(last4, "last4");
        Objects.requireNonNull(expiry, "expiry");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!last4.matches("\\d{4}")) {
            throw new IllegalArgumentException("last4 must be exactly 4 digits");
        }
    }

    /** Factory: issues a new active card that expires {@value #VALIDITY_YEARS} years from today. */
    public static Card issue(UUID accountId, LocalDate today, Instant now) {
        String last4 = "%04d".formatted(RANDOM.nextInt(10_000));
        YearMonth expiry = YearMonth.from(today).plusYears(VALIDITY_YEARS);
        return new Card(UUID.randomUUID(), accountId, last4, expiry, CardStatus.ACTIVE, now);
    }

    /** A card is valid through the <b>last day</b> of its expiry month ("valid thru 10/30"). */
    public boolean isExpiredOn(LocalDate date) {
        return date.isAfter(expiry.atEndOfMonth());
    }

    public boolean isActive() {
        return status == CardStatus.ACTIVE;
    }

    /** Returns a blocked copy. Blocking is idempotent: blocking a blocked card is a no-op. */
    public Card block() {
        return status == CardStatus.BLOCKED ? this
                : new Card(id, accountId, last4, expiry, CardStatus.BLOCKED, createdAt);
    }
}
