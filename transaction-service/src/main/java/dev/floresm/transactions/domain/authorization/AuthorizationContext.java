package dev.floresm.transactions.domain.authorization;

import dev.floresm.transactions.domain.account.Account;
import dev.floresm.transactions.domain.card.Card;
import dev.floresm.transactions.domain.money.Money;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Everything a rule may look at, bundled in one immutable object ("parameter object" pattern).
 * Adding a new piece of data later (e.g. the merchant) means changing this record, not the
 * signature of every rule.
 *
 * @param today the business date, taken from an injected {@code java.time.Clock} (never
 *              {@code LocalDate.now()} directly) so tests can control time
 */
public record AuthorizationContext(Card card, Account account, Money amount, LocalDate today) {

    public AuthorizationContext {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(today, "today");
    }
}
