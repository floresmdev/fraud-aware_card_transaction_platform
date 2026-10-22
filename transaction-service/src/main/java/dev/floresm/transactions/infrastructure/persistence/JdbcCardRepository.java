package dev.floresm.transactions.infrastructure.persistence;

import dev.floresm.transactions.domain.card.Card;
import dev.floresm.transactions.domain.card.CardRepository;
import dev.floresm.transactions.domain.card.CardStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

/** SQL implementation of {@link CardRepository}. Same conventions as JdbcAccountRepository. */
@Repository
public class JdbcCardRepository implements CardRepository {

    private final JdbcClient jdbc;

    public JdbcCardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Card card) {
        jdbc.sql("""
                        INSERT INTO cards (id, account_id, last4, expiry_year, expiry_month, status, created_at)
                        VALUES (:id, :accountId, :last4, :expiryYear, :expiryMonth, :status, :createdAt)
                        """)
                .param("id", card.id())
                .param("accountId", card.accountId())
                .param("last4", card.last4())
                .param("expiryYear", card.expiry().getYear())
                .param("expiryMonth", card.expiry().getMonthValue())
                // Enums are stored by NAME, never by ordinal(): reordering the enum would
                // silently change the meaning of every stored row.
                .param("status", card.status().name())
                .param("createdAt", SqlTime.toDb(card.createdAt()))
                .update();
    }

    @Override
    public Optional<Card> findById(UUID id) {
        return jdbc.sql("""
                        SELECT id, account_id, last4, expiry_year, expiry_month, status, created_at
                          FROM cards
                         WHERE id = :id
                        """)
                .param("id", id)
                .query(JdbcCardRepository::mapRow)
                .optional();
    }

    @Override
    public void updateStatus(Card card) {
        jdbc.sql("UPDATE cards SET status = :status WHERE id = :id")
                .param("status", card.status().name())
                .param("id", card.id())
                .update();
    }

    private static Card mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Card(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("last4"),
                YearMonth.of(rs.getInt("expiry_year"), rs.getInt("expiry_month")),
                CardStatus.valueOf(rs.getString("status")),
                SqlTime.fromDb(rs, "created_at"));
    }
}
