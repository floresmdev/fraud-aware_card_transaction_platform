package dev.floresm.transactions.domain.transaction;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for transactions. Implementation: infrastructure/persistence/JdbcTransactionRepository.java. */
public interface TransactionRepository {

    /**
     * Inserts a transaction. The table has a UNIQUE constraint on the idempotency key, so a
     * concurrent duplicate fails with Spring's {@code DuplicateKeyException}.
     */
    void insert(Transaction transaction);

    Optional<Transaction> findById(UUID id);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /**
     * Keyset ("seek") pagination: returns up to {@code limit} transactions of a card, newest first,
     * that come strictly <i>after</i> the given position in that order. Pass {@code null} as the
     * position for the first page. See TransactionHistoryService for why keyset beats OFFSET.
     */
    List<Transaction> findByCardBefore(UUID cardId, Instant beforeCreatedAt, UUID beforeId, int limit);
}
