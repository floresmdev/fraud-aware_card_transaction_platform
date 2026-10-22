package dev.floresm.transactions.domain.card;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for cards. Implementation: infrastructure/persistence/JdbcCardRepository.java. */
public interface CardRepository {

    void insert(Card card);

    Optional<Card> findById(UUID id);

    void updateStatus(Card card);
}
