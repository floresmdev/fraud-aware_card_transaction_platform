package dev.floresm.transactions.infrastructure.persistence;

import dev.floresm.transactions.domain.account.Account;
import dev.floresm.transactions.domain.account.AccountRepository;
import dev.floresm.transactions.domain.money.Money;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL implementation of {@link AccountRepository} (the <b>adapter</b> for the domain's <b>port</b>).
 *
 * <p>{@link JdbcClient} is Spring's fluent wrapper over JDBC: named parameters, automatic
 * connection/statement cleanup, translation of SQL errors into Spring's {@code DataAccessException}
 * hierarchy, and participation in the current {@code @Transactional} transaction. All SQL uses
 * bind parameters ({@code :id}), never string concatenation, so SQL injection is impossible here.
 */
@Repository
public class JdbcAccountRepository implements AccountRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, holder_name, currency, credit_limit_minor, available_credit_minor, created_at
              FROM accounts
            """;

    private final JdbcClient jdbc;

    public JdbcAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Account account) {
        jdbc.sql("""
                        INSERT INTO accounts (id, holder_name, currency, credit_limit_minor,
                                              available_credit_minor, created_at)
                        VALUES (:id, :holderName, :currency, :creditLimit, :availableCredit, :createdAt)
                        """)
                .param("id", account.id())
                .param("holderName", account.holderName())
                .param("currency", account.currency().getCurrencyCode())
                .param("creditLimit", account.creditLimit().minorUnits())
                .param("availableCredit", account.availableCredit().minorUnits())
                .param("createdAt", SqlTime.toDb(account.createdAt()))
                .update();
    }

    @Override
    public Optional<Account> findById(UUID id) {
        return jdbc.sql(SELECT_COLUMNS + " WHERE id = :id")
                .param("id", id)
                .query(JdbcAccountRepository::mapRow)
                .optional();
    }

    @Override
    public Optional<Account> findByIdForUpdate(UUID id) {
        // FOR UPDATE: row-level exclusive lock until COMMIT/ROLLBACK. Readers without FOR UPDATE
        // are NOT blocked (PostgreSQL MVCC: readers see the last committed version).
        return jdbc.sql(SELECT_COLUMNS + " WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(JdbcAccountRepository::mapRow)
                .optional();
    }

    @Override
    public void updateAvailableCredit(Account account) {
        int updated = jdbc.sql("UPDATE accounts SET available_credit_minor = :available WHERE id = :id")
                .param("available", account.availableCredit().minorUnits())
                .param("id", account.id())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("expected to update 1 account row, updated " + updated);
        }
    }

    /** Maps one SQL row to a domain object. A method reference to this is a {@code RowMapper}. */
    private static Account mapRow(ResultSet rs, int rowNum) throws SQLException {
        Currency currency = Currency.getInstance(rs.getString("currency"));
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getString("holder_name"),
                new Money(rs.getLong("credit_limit_minor"), currency),
                new Money(rs.getLong("available_credit_minor"), currency),
                SqlTime.fromDb(rs, "created_at"));
    }
}
