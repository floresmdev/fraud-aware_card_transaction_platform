package dev.floresm.transactions.api.dto;

import dev.floresm.transactions.domain.transaction.DeclineReason;
import dev.floresm.transactions.domain.transaction.Transaction;
import dev.floresm.transactions.domain.transaction.TransactionStatus;

import java.time.Instant;
import java.util.UUID;

/** Note what is NOT exposed: the idempotency key and the request fingerprint are internal. */
public record TransactionResponse(
        UUID id,
        UUID cardId,
        UUID accountId,
        MoneyDto amount,
        MerchantDto merchant,
        TransactionStatus status,
        DeclineReason declineReason,
        Instant createdAt
) {

    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(t.id(), t.cardId(), t.accountId(), MoneyDto.from(t.amount()),
                MerchantDto.from(t.merchant()), t.status(), t.declineReason(), t.createdAt());
    }
}
