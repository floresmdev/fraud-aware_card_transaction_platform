package dev.floresm.transactions.domain.account;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence <b>port</b> for accounts: the domain says <i>what</i> it needs, not <i>how</i> it is
 * stored. The implementation lives in infrastructure/persistence/JdbcAccountRepository.java.
 *
 * <p>This is the Dependency Inversion Principle (the "D" in SOLID): high-level code (the services)
 * depends on this interface, and the low-level detail (SQL) depends on it too. Payoff: unit tests
 * replace it with a Mockito mock, and we could swap PostgreSQL for something else without touching
 * the services.
 */
public interface AccountRepository {

    void insert(Account account);

    Optional<Account> findById(UUID id);

    /**
     * Loads the account and takes a row-level write lock ({@code SELECT ... FOR UPDATE}) that is held
     * until the surrounding database transaction ends. Any other transaction trying to lock the same
     * row waits. This is how we prevent two concurrent authorizations from both spending the same
     * credit. Must be called inside a transaction.
     */
    Optional<Account> findByIdForUpdate(UUID id);

    void updateAvailableCredit(Account account);
}
