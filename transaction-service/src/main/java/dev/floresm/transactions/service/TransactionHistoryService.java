package dev.floresm.transactions.service;

import dev.floresm.transactions.domain.card.CardRepository;
import dev.floresm.transactions.domain.exception.NotFoundException;
import dev.floresm.transactions.domain.transaction.Transaction;
import dev.floresm.transactions.domain.transaction.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Read side: single transaction lookup and a card's transaction history.
 *
 * <h2>Pagination: keyset (cursor) instead of OFFSET</h2>
 * {@code LIMIT 20 OFFSET 100000} makes PostgreSQL read and throw away 100,000 rows: cost grows
 * linearly with the page number, O(offset + limit). Worse, if a new transaction arrives while the
 * user is paging, every row shifts by one and the user sees a duplicate or misses one.
 *
 * <p>Keyset pagination remembers <i>where the last page ended</i> (created_at and id of the last
 * row) and asks for rows "after that point" in the sort order. With an index on
 * (card_id, created_at DESC, id DESC) the database seeks straight to that point in the B-tree,
 * O(log n + limit) for any page, and inserts do not shift anything. The {@code id} is a tie-breaker
 * because two transactions can share a timestamp; the sort key must be unique.
 *
 * <p>The cursor is opaque to clients (Base64), so we can change its format without breaking them.
 */
@Service
public class TransactionHistoryService {

    public static final int MAX_PAGE_SIZE = 100;

    private final TransactionRepository transactions;
    private final CardRepository cards;

    public TransactionHistoryService(TransactionRepository transactions, CardRepository cards) {
        this.transactions = transactions;
        this.cards = cards;
    }

    /** One page of results plus the cursor for the next page ({@code null} when there is none). */
    public record Page(List<Transaction> items, String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }

    @Transactional(readOnly = true)
    public Transaction getTransaction(UUID id) {
        return transactions.findById(id).orElseThrow(() -> new NotFoundException("Transaction", id));
    }

    @Transactional(readOnly = true)
    public Page history(UUID cardId, int limit, String cursor) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (cards.findById(cardId).isEmpty()) {
            throw new NotFoundException("Card", cardId);
        }
        Position after = cursor == null ? null : Position.decode(cursor);

        // Fetch one extra row: if it exists there is a next page. Saves a separate COUNT(*) query.
        List<Transaction> rows = transactions.findByCardBefore(
                cardId, after == null ? null : after.createdAt(), after == null ? null : after.id(), limit + 1);

        boolean hasMore = rows.size() > limit;
        List<Transaction> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore ? Position.of(page.getLast()).encode() : null;
        return new Page(page, nextCursor);
    }

    /** Position in the (created_at DESC, id DESC) ordering, serialized as the opaque cursor. */
    record Position(Instant createdAt, UUID id) {

        static Position of(Transaction t) {
            return new Position(t.createdAt(), t.id());
        }

        String encode() {
            long micros = ChronoUnit.MICROS.between(Instant.EPOCH, createdAt);
            String raw = micros + ":" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Position decode(String cursor) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int sep = raw.indexOf(':');
                long micros = Long.parseLong(raw.substring(0, sep));
                return new Position(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), UUID.fromString(raw.substring(sep + 1)));
            } catch (RuntimeException e) {
                // Covers bad Base64, a missing ':', a bad number or a bad UUID.
                throw new InvalidCursorException();
            }
        }
    }

    /** The client sent a cursor we did not produce (or tampered with it). Mapped to HTTP 400. */
    public static class InvalidCursorException extends RuntimeException {
        public InvalidCursorException() {
            super("Invalid pagination cursor");
        }
    }
}
