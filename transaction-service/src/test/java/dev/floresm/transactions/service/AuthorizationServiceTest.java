package dev.floresm.transactions.service;

import dev.floresm.transactions.domain.account.Account;
import dev.floresm.transactions.domain.account.AccountRepository;
import dev.floresm.transactions.domain.authorization.rules.CardMustBeActiveRule;
import dev.floresm.transactions.domain.authorization.rules.CardMustNotBeExpiredRule;
import dev.floresm.transactions.domain.authorization.rules.CurrencyMustMatchAccountRule;
import dev.floresm.transactions.domain.authorization.rules.SufficientCreditRule;
import dev.floresm.transactions.domain.card.Card;
import dev.floresm.transactions.domain.card.CardRepository;
import dev.floresm.transactions.domain.card.CardStatus;
import dev.floresm.transactions.domain.exception.IdempotencyKeyReuseException;
import dev.floresm.transactions.domain.exception.NotFoundException;
import dev.floresm.transactions.domain.fraud.FraudAssessment;
import dev.floresm.transactions.domain.fraud.FraudDecision;
import dev.floresm.transactions.domain.fraud.FraudScreener;
import dev.floresm.transactions.domain.money.Money;
import dev.floresm.transactions.domain.transaction.DeclineReason;
import dev.floresm.transactions.domain.transaction.Merchant;
import dev.floresm.transactions.domain.transaction.Transaction;
import dev.floresm.transactions.domain.transaction.TransactionRepository;
import dev.floresm.transactions.domain.transaction.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test of the orchestration logic with all collaborators replaced by <b>Mockito mocks</b>.
 *
 * <p>A mock is a fake object generated at runtime that implements the same interface. We
 * <i>stub</i> it ({@code when(x).thenReturn(y)}) to control its answers and <i>verify</i> it
 * ({@code verify(mock).method()}) to check how our code used it. This only works because
 * AuthorizationService depends on interfaces (ports): the payoff of Dependency Inversion.
 *
 * <p>What we do NOT test here: the SQL, the row lock, the UNIQUE constraint. Mocks cannot prove
 * those; TransactionApiIT does, against a real PostgreSQL.
 */
@ExtendWith(MockitoExtension.class)
class AuthorizationServiceTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final Merchant MERCHANT = new Merchant("OXXO Reforma", "5411", "MX");

    @Mock CardRepository cards;
    @Mock AccountRepository accounts;
    @Mock TransactionRepository transactions;
    @Mock FraudScreener fraudScreener;
    @Mock PlatformTransactionManager txManager;

    private AuthorizationService service;
    private Account account;
    private Card card;

    @BeforeEach
    void setUp() {
        // A real TransactionTemplate over a mocked transaction manager: begin/commit/rollback become no-ops.
        TransactionTemplate template = new TransactionTemplate(txManager);
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new AuthorizationService(cards, accounts, transactions,
                List.of(new CardMustBeActiveRule(), new CardMustNotBeExpiredRule(),
                        new CurrencyMustMatchAccountRule(), new SufficientCreditRule()),
                fraudScreener, template, fixedClock);

        account = Account.open("Ada", new Money(100_00, USD), NOW);
        card = new Card(UUID.randomUUID(), account.id(), "1234", YearMonth.of(2030, 10), CardStatus.ACTIVE, NOW);
    }

    private AuthorizeCommand command(long cents, String key) {
        return new AuthorizeCommand(card.id(), new Money(cents, USD), MERCHANT, key);
    }

    private void givenCardAndAccountExist() {
        when(transactions.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(cards.findById(card.id())).thenReturn(Optional.of(card));
        when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    }

    @Test
    void approvesAndReservesCreditWhenEverythingPasses() {
        givenCardAndAccountExist();
        when(fraudScreener.screen(any())).thenReturn(FraudAssessment.approve("ok"));
        when(accounts.findByIdForUpdate(account.id())).thenReturn(Optional.of(account));

        AuthorizationResult result = service.authorize(command(30_00, "key-00000001"));

        assertThat(result.replayed()).isFalse();
        assertThat(result.transaction().status()).isEqualTo(TransactionStatus.APPROVED);

        // ArgumentCaptor grabs the argument our code passed to the mock so we can inspect it.
        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accounts).updateAvailableCredit(saved.capture());
        assertThat(saved.getValue().availableCredit()).isEqualTo(new Money(70_00, USD));
        verify(transactions).insert(result.transaction());
    }

    @Test
    void declinesWithoutCallingFraudOrLockingWhenARuleFails() {
        card = card.block();
        givenCardAndAccountExist();

        AuthorizationResult result = service.authorize(command(10_00, "key-00000002"));

        assertThat(result.transaction().status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(result.transaction().declineReason()).isEqualTo(DeclineReason.CARD_NOT_ACTIVE);
        verifyNoInteractions(fraudScreener);
        verify(accounts, never()).findByIdForUpdate(any());
        verify(accounts, never()).updateAvailableCredit(any());
    }

    @Test
    void fraudDeclineIsRecordedAndReservesNothing() {
        givenCardAndAccountExist();
        when(fraudScreener.screen(any()))
                .thenReturn(new FraudAssessment(FraudDecision.DECLINE, 0.97, List.of("velocity")));

        AuthorizationResult result = service.authorize(command(10_00, "key-00000003"));

        assertThat(result.transaction().declineReason()).isEqualTo(DeclineReason.SUSPECTED_FRAUD);
        verify(accounts, never()).updateAvailableCredit(any());
    }

    @Test
    void fraudReviewReservesCreditAndIsPendingReview() {
        givenCardAndAccountExist();
        when(fraudScreener.screen(any()))
                .thenReturn(new FraudAssessment(FraudDecision.REVIEW, 0.6, List.of("new country")));
        when(accounts.findByIdForUpdate(account.id())).thenReturn(Optional.of(account));

        AuthorizationResult result = service.authorize(command(10_00, "key-00000004"));

        assertThat(result.transaction().status()).isEqualTo(TransactionStatus.PENDING_REVIEW);
        verify(accounts).updateAvailableCredit(any());
    }

    @Test
    void reChecksCreditUnderTheLockBecauseTheSnapshotMayBeStale() {
        // Snapshot says 100.00 available, but by the time we hold the lock another purchase used it all.
        givenCardAndAccountExist();
        when(fraudScreener.screen(any())).thenReturn(FraudAssessment.approve("ok"));
        when(accounts.findByIdForUpdate(account.id())).thenReturn(Optional.of(account.reserve(new Money(95_00, USD))));

        AuthorizationResult result = service.authorize(command(10_00, "key-00000005"));

        assertThat(result.transaction().declineReason()).isEqualTo(DeclineReason.INSUFFICIENT_CREDIT);
        verify(accounts, never()).updateAvailableCredit(any());
    }

    @Test
    void retryWithSameKeyAndSameBodyReturnsTheStoredResult() {
        AuthorizeCommand cmd = command(10_00, "key-00000006");
        Transaction stored = new Transaction(UUID.randomUUID(), card.id(), account.id(), cmd.amount(), MERCHANT,
                TransactionStatus.APPROVED, null, cmd.idempotencyKey(), cmd.fingerprint(), NOW);
        when(transactions.findByIdempotencyKey(cmd.idempotencyKey())).thenReturn(Optional.of(stored));

        AuthorizationResult result = service.authorize(cmd);

        assertThat(result.replayed()).isTrue();
        assertThat(result.transaction()).isSameAs(stored);
        verifyNoInteractions(cards, accounts, fraudScreener);
    }

    @Test
    void sameKeyWithADifferentBodyIsRejected() {
        AuthorizeCommand original = command(10_00, "key-00000007");
        Transaction stored = new Transaction(UUID.randomUUID(), card.id(), account.id(), original.amount(), MERCHANT,
                TransactionStatus.APPROVED, null, original.idempotencyKey(), original.fingerprint(), NOW);
        when(transactions.findByIdempotencyKey(original.idempotencyKey())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.authorize(command(99_00, "key-00000007")))
                .isInstanceOf(IdempotencyKeyReuseException.class);
    }

    @Test
    void losingAnInsertRaceReturnsTheWinnersResult() {
        AuthorizeCommand cmd = command(10_00, "key-00000008");
        Transaction winner = new Transaction(UUID.randomUUID(), card.id(), account.id(), cmd.amount(), MERCHANT,
                TransactionStatus.APPROVED, null, cmd.idempotencyKey(), cmd.fingerprint(), NOW);
        // First lookup: nothing yet. Lookup after the failed insert: the winner's row.
        when(transactions.findByIdempotencyKey(cmd.idempotencyKey()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(cards.findById(card.id())).thenReturn(Optional.of(card));
        when(accounts.findById(account.id())).thenReturn(Optional.of(account));
        when(fraudScreener.screen(any())).thenReturn(FraudAssessment.approve("ok"));
        when(accounts.findByIdForUpdate(account.id())).thenReturn(Optional.of(account));
        doThrow(new DuplicateKeyException("uq_transactions_idempotency_key")).when(transactions).insert(any());

        AuthorizationResult result = service.authorize(cmd);

        assertThat(result.replayed()).isTrue();
        assertThat(result.transaction()).isSameAs(winner);
    }

    @Test
    void unknownCardIsNotFound() {
        when(transactions.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(cards.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authorize(command(10_00, "key-00000009")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void fingerprintIsUnambiguousForShiftedFieldBoundaries() {
        UUID cardId = UUID.randomUUID();
        Money amount = new Money(100, USD);
        var a = new AuthorizeCommand(cardId, amount, new Merchant("AB", "5411", "MX"), "k");
        var b = new AuthorizeCommand(cardId, amount, new Merchant("A", "5411", "MX"), "k");
        assertThat(a.fingerprint()).isNotEqualTo(b.fingerprint()).hasSize(64);
    }
}
