package dev.floresm.transactions.domain.fraud;

import dev.floresm.transactions.domain.money.Money;
import dev.floresm.transactions.domain.transaction.Merchant;

import java.time.Instant;
import java.util.UUID;

/** What we tell the fraud screener about a transaction. Kept separate from Transaction on purpose:
 *  the screener should only see what it needs (data minimization). */
public record FraudScreeningRequest(
        UUID cardId,
        UUID accountId,
        Money amount,
        Merchant merchant,
        Instant requestedAt
) {
}
