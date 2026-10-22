package dev.floresm.transactions.infrastructure.persistence;

import dev.floresm.transactions.domain.money.Money;
import dev.floresm.transactions.domain.transaction.DeclineReason;
import dev.floresm.transactions.domain.transaction.Merchant;
import dev.floresm.transactions.domain.transaction.Transaction;
import dev.floresm.transactions.domain.transaction.TransactionRepository;
import dev.floresm.transactions.domain.transaction.TransactionStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL implementation of {@link TransactionRepository}. */
@Repository
public class JdbcTransactionRepository implements TransactionRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, card_id, account_id, amount_minor, currency, merchant_name,
                   merchant_category_code, merchant_country, status, decline_reason,
                   idempotency_key, request_fingerprint, created_at
              FROM transactions
            """;

    private final JdbcClient jdbc;

    public JdbcTransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Transaction t) {
        jdbc.sql("""
                        INSERT INTO transactions (id, card_id, account_id, amount_minor, currency, merchant_name,
                                                  merchant_category_code, merchant_country, status, decline_reason,
                                                  idempotency_key, request_fingerprint, created_at)
                        VALUES (:id, :cardId, :accountId, :amount, :currency, :merchantName,
                                :mcc, :country, :status, :declineReason,
                                :idempotencyKey, :fingerprint, :createdAt)
                        """)
                .param("id", t.id())
                .param("cardId", t.cardId())
                .param("accountId", t.accountId())
                .param("amount", t.amount().minorUnits())
                .param("currency", t.amount().currency().getCurrencyCode())
                .param("merchantName", t.merchant().name())
                .param("mcc", t.merchant().categoryCode())
                .param("country", t.merchant().country())
                .param("status", t.status().name())
                .param("declineReason", t.declineReason() == null ? null : t.declineReason().name())
                .param("idempotencyKey", t.idempotencyKey())
                .param("fingerprint", t.requestFingerprint())
                .param("createdAt", SqlTime.toDb(t.createdAt()))
                .update();
    }

    @Override
    public Optional<Transaction> findById(UUID id) {
        return jdbc.sql(SELECT_COLUMNS + " WHERE id = :id")
                .param("id", id)
                .query(JdbcTransactionRepository::mapRow)
                .optional();
    }

    @Override
    public Optional<Transaction> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql(SELECT_COLUMNS + " WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(JdbcTransactionRepository::mapRow)
                .optional();
    }

    @Override
    public List<Transaction> findByCardBefore(UUID cardId, Instant beforeCreatedAt, UUID beforeId, int limit) {
        if (beforeCreatedAt == null) {
            return jdbc.sql(SELECT_COLUMNS + """
                             WHERE card_id = :cardId
                             ORDER BY created_at DESC, id DESC
                             LIMIT :limit
                            """)
                    .param("cardId", cardId)
                    .param("limit", limit)
                    .query(JdbcTransactionRepository::mapRow)
                    .list();
        }
        // Row-value comparison: (a, b) < (x, y) means a < x OR (a = x AND b < y), i.e.
        // "strictly after the cursor in DESC order". PostgreSQL can use the composite index for it.
        return jdbc.sql(SELECT_COLUMNS + """
                         WHERE card_id = :cardId
                           AND (created_at, id) < (:beforeCreatedAt, :beforeId)
                         ORDER BY created_at DESC, id DESC
                         LIMIT :limit
                        """)
                .param("cardId", cardId)
                .param("beforeCreatedAt", SqlTime.toDb(beforeCreatedAt))
                .param("beforeId", beforeId)
                .param("limit", limit)
                .query(JdbcTransactionRepository::mapRow)
                .list();
    }

    private static Transaction mapRow(ResultSet rs, int rowNum) throws SQLException {
        String declineReason = rs.getString("decline_reason");
        return new Transaction(
                rs.getObject("id", UUID.class),
                rs.getObject("card_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                new Money(rs.getLong("amount_minor"), Currency.getInstance(rs.getString("currency"))),
                new Merchant(
                        rs.getString("merchant_name"),
                        rs.getString("merchant_category_code"),
                        rs.getString("merchant_country")),
                TransactionStatus.valueOf(rs.getString("status")),
                declineReason == null ? null : DeclineReason.valueOf(declineReason),
                rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"),
                SqlTime.fromDb(rs, "created_at"));
    }
}
