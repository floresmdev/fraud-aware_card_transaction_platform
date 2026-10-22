package dev.floresm.transactions.service;

import dev.floresm.transactions.domain.account.Account;
import dev.floresm.transactions.domain.account.AccountRepository;
import dev.floresm.transactions.domain.card.Card;
import dev.floresm.transactions.domain.card.CardRepository;
import dev.floresm.transactions.domain.exception.NotFoundException;
import dev.floresm.transactions.domain.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Use cases for accounts and cards. The service layer orchestrates: it loads domain objects through
 * repository ports, asks them to do their thing, and saves the result. Business rules live in the
 * domain classes, not here. That keeps services thin and the rules unit-testable without Spring.
 *
 * <p><b>Constructor injection</b> (no {@code @Autowired} on fields): dependencies are explicit and
 * {@code final}, the object is fully initialized once constructed, and a unit test can just call
 * {@code new AccountService(mockRepo, ...)}.
 */
@Service
public class AccountService {

    private final AccountRepository accounts;
    private final CardRepository cards;
    private final Clock clock;

    public AccountService(AccountRepository accounts, CardRepository cards, Clock clock) {
        this.accounts = accounts;
        this.cards = cards;
        this.clock = clock;
    }

    @Transactional
    public Account openAccount(String holderName, Money creditLimit) {
        Account account = Account.open(holderName.strip(), creditLimit, Timestamps.now(clock));
        accounts.insert(account);
        return account;
    }

    @Transactional(readOnly = true)
    public Account getAccount(UUID accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new NotFoundException("Account", accountId));
    }

    @Transactional
    public Card issueCard(UUID accountId) {
        Account account = getAccount(accountId);
        Card card = Card.issue(account.id(), LocalDate.now(clock.withZone(ZoneOffset.UTC)), Timestamps.now(clock));
        cards.insert(card);
        return card;
    }

    @Transactional(readOnly = true)
    public Card getCard(UUID cardId) {
        return cards.findById(cardId).orElseThrow(() -> new NotFoundException("Card", cardId));
    }

    @Transactional
    public Card blockCard(UUID cardId) {
        Card blocked = getCard(cardId).block();
        cards.updateStatus(blocked);
        return blocked;
    }
}
