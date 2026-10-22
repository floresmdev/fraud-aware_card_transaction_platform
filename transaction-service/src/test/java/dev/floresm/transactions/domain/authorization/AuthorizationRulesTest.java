package dev.floresm.transactions.domain.authorization;

import dev.floresm.transactions.domain.account.Account;
import dev.floresm.transactions.domain.authorization.rules.CardMustBeActiveRule;
import dev.floresm.transactions.domain.authorization.rules.CardMustNotBeExpiredRule;
import dev.floresm.transactions.domain.authorization.rules.CurrencyMustMatchAccountRule;
import dev.floresm.transactions.domain.authorization.rules.SufficientCreditRule;
import dev.floresm.transactions.domain.card.Card;
import dev.floresm.transactions.domain.card.CardStatus;
import dev.floresm.transactions.domain.money.Money;
import dev.floresm.transactions.domain.transaction.DeclineReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Each rule is a small pure function, so each test is a few lines. That is the payoff of the Strategy design. */
class AuthorizationRulesTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    private final Account account = Account.open("Ada", new Money(100_00, USD), NOW);
    private final Card activeCard = new Card(UUID.randomUUID(), account.id(), "1234", YearMonth.of(2030, 10), CardStatus.ACTIVE, NOW);

    private AuthorizationContext context(Card card, Money amount) {
        return new AuthorizationContext(card, account, amount, TODAY);
    }

    @Test
    void cardMustBeActive() {
        var rule = new CardMustBeActiveRule();
        assertThat(rule.evaluate(context(activeCard, new Money(1, USD)))).isEqualTo(RuleResult.pass());
        assertThat(rule.evaluate(context(activeCard.block(), new Money(1, USD))))
                .isEqualTo(RuleResult.fail(DeclineReason.CARD_NOT_ACTIVE));
    }

    @Test
    void cardMustNotBeExpired() {
        var rule = new CardMustNotBeExpiredRule();
        Card expired = new Card(UUID.randomUUID(), account.id(), "1234", YearMonth.of(2026, 9), CardStatus.ACTIVE, NOW);
        assertThat(rule.evaluate(context(expired, new Money(1, USD))))
                .isEqualTo(RuleResult.fail(DeclineReason.CARD_EXPIRED));
        assertThat(rule.evaluate(context(activeCard, new Money(1, USD)))).isEqualTo(RuleResult.pass());
    }

    @Test
    void currencyMustMatchAccount() {
        var rule = new CurrencyMustMatchAccountRule();
        assertThat(rule.evaluate(context(activeCard, new Money(1, Currency.getInstance("MXN")))))
                .isEqualTo(RuleResult.fail(DeclineReason.CURRENCY_MISMATCH));
    }

    @Test
    void sufficientCreditAllowsExactlyTheAvailableAmount() {
        var rule = new SufficientCreditRule();
        assertThat(rule.evaluate(context(activeCard, new Money(100_00, USD)))).isEqualTo(RuleResult.pass());
        assertThat(rule.evaluate(context(activeCard, new Money(100_01, USD))))
                .isEqualTo(RuleResult.fail(DeclineReason.INSUFFICIENT_CREDIT));
    }

    @Test
    void sufficientCreditDoesNotThrowOnCurrencyMismatch() {
        // Robust even if rule order changes: a mismatch is "cannot cover", not a crash.
        assertThat(new SufficientCreditRule().evaluate(context(activeCard, new Money(1, Currency.getInstance("MXN")))))
                .isEqualTo(RuleResult.fail(DeclineReason.INSUFFICIENT_CREDIT));
    }
}
