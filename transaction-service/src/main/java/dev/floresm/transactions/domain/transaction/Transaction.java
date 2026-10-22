package dev.floresm.transactions.domain.transaction;

import dev.floresm.transactions.domain.money.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A single authorization attempt, approved or not. Declines are stored too: they are evidence for
 * customer support, for fraud analysis and for idempotent replays.
 *
 * <p>Transactions are <b>append-only</b>: once written they are never updated. Later events
 * (capture, refund, chargeback) would be new rows referencing this one. Append-only ledgers are
 * auditable and avoid lost-update bugs.
 *
 * @param idempotencyKey     the client-supplied key that makes retries safe (see AuthorizationService)
 * @param requestFingerprint SHA-256 of the request contents, used to detect a key reused with a
 *                           <i>different</i> request, which is a client bug we must reject
 */
public record Transaction(
        UUID id,
        UUID cardId,
        UUID accountId,
        Money amount,
        Merchant merchant,
        TransactionStatus status,
        DeclineReason declineReason,
        String idempotencyKey,
        String requestFingerprint,
        Instant createdAt
) {

    public Transaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cardId, "cardId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("transaction amount must be positive");
        }
        // A decline always has a reason, and nothing else has one. Mirrored by a DB CHECK constraint.
        if ((status == TransactionStatus.DECLINED) != (declineReason != null)) {
            throw new IllegalArgumentException("declineReason must be set if and only if status is DECLINED");
        }
    }

    public boolean reservesCredit() {
        return status == TransactionStatus.APPROVED || status == TransactionStatus.PENDING_REVIEW;
    }
}
