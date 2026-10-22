package dev.floresm.transactions.api.dto;

import dev.floresm.transactions.domain.card.Card;
import dev.floresm.transactions.domain.card.CardStatus;

import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

/** {@code expiry} serializes as "2030-10" (ISO-8601 year-month). */
public record CardResponse(
        UUID id,
        UUID accountId,
        String last4,
        YearMonth expiry,
        CardStatus status,
        Instant createdAt
) {

    public static CardResponse from(Card c) {
        return new CardResponse(c.id(), c.accountId(), c.last4(), c.expiry(), c.status(), c.createdAt());
    }
}
