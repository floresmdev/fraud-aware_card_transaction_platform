package dev.floresm.transactions.domain.account;

import dev.floresm.transactions.domain.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    private static Money usd(long cents) {
        return new Money(cents, USD);
    }

    @Test
    void newAccountHasFullCreditAvailable() {
        Account account = Account.open("Ada Lovelace", usd(100_00), NOW);
        assertThat(account.availableCredit()).isEqualTo(usd(100_00));
    }

    @Test
    void reserveReturnsANewAccountWithReducedAvailableCredit() {
        Account account = Account.open("Ada Lovelace", usd(100_00), NOW);
        Account after = account.reserve(usd(30_00));

        assertThat(after.availableCredit()).isEqualTo(usd(70_00));
        assertThat(after.creditLimit()).isEqualTo(usd(100_00));
        assertThat(account.availableCredit()).as("original is unchanged").isEqualTo(usd(100_00));
    }

    @Test
    void canSpendExactlyTheAvailableCredit() {
        Account account = Account.open("Ada Lovelace", usd(100_00), NOW);
        assertThat(account.reserve(usd(100_00)).availableCredit()).isEqualTo(usd(0));
    }

    @Test
    void reservingMoreThanAvailableIsRejected() {
        Account account = Account.open("Ada Lovelace", usd(100_00), NOW);
        assertThatThrownBy(() -> account.reserve(usd(100_01))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invariantsAreEnforcedOnConstruction() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new Account(id, "X", usd(100), usd(101), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Account(id, "X", usd(100), usd(-1), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Account(id, " ", usd(100), usd(100), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
