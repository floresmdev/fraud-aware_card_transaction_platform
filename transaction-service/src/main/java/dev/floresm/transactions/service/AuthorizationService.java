package dev.floresm.transactions.service;

import dev.floresm.transactions.domain.account.Account;
import dev.floresm.transactions.domain.account.AccountRepository;
import dev.floresm.transactions.domain.authorization.AuthorizationContext;
import dev.floresm.transactions.domain.authorization.AuthorizationRule;
import dev.floresm.transactions.domain.authorization.RuleResult;
import dev.floresm.transactions.domain.card.Card;
import dev.floresm.transactions.domain.card.CardRepository;
import dev.floresm.transactions.domain.exception.IdempotencyKeyReuseException;
import dev.floresm.transactions.domain.exception.NotFoundException;
import dev.floresm.transactions.domain.fraud.FraudAssessment;
import dev.floresm.transactions.domain.fraud.FraudDecision;
import dev.floresm.transactions.domain.fraud.FraudScreener;
import dev.floresm.transactions.domain.fraud.FraudScreeningRequest;
import dev.floresm.transactions.domain.transaction.DeclineReason;
import dev.floresm.transactions.domain.transaction.Transaction;
import dev.floresm.transactions.domain.transaction.TransactionRepository;
import dev.floresm.transactions.domain.transaction.TransactionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The heart of the service: decides whether a card transaction is approved, declined or sent to
 * review, and records the decision exactly once.
 *
 * <h2>Three problems this class solves</h2>
 * <ol>
 *   <li><b>Retries must not double-charge (idempotency).</b> The client sends an Idempotency-Key.
 *       If we have already processed that key, we return the stored result instead of authorizing again.</li>
 *   <li><b>Concurrent requests must not overspend (race condition).</b> Two requests of 60 against
 *       a 100 limit, arriving at the same millisecond, must not both see "100 available" and both
 *       approve. We lock the account row ({@code SELECT ... FOR UPDATE}) so they run one at a time.</li>
 *   <li><b>A slow fraud check must not block everybody (lock scope).</b> The fraud call is a network
 *       call (Phase 3) that can take hundreds of milliseconds. If we held the account lock during that
 *       call, every other purchase on the same account would queue behind it, and we would also hold
 *       a pooled DB connection. So the fraud call happens <i>before</i> the locked transaction.</li>
 * </ol>
 *
 * <h2>The flow ("check, screen, then lock and re-check")</h2>
 * <pre>
 *  1. Idempotency fast path: key already used? -> return stored result (or 422 if body differs)
 *  2. Load card + account WITHOUT a lock, run the eligibility rules on that snapshot
 *       -> any rule fails? decline now, skip the fraud call (cheap, and nothing to protect)
 *  3. Fraud screening, outside any DB transaction
 *  4. Short DB transaction:
 *       lock account row -> re-run rules on FRESH data -> reserve credit
 *       -> insert transaction -> commit (releases the lock)
 * </pre>
 * <b>What if two requests with the same key both get past step 1?</b> Both reach step 4. The
 * transactions table has a UNIQUE constraint on the key, so only one INSERT can succeed. The
 * loser's INSERT fails, its whole DB transaction rolls back (including the credit it reserved:
 * that is <b>atomicity</b>, the "A" in ACID), and we return the winner's result. We do not need
 * clever locking for idempotency; we let the database enforce it.
 * <p>
 * Step 4 re-runs the rules because the snapshot from step 2 may be stale: another purchase may have
 * used the credit in the meantime. The check under the lock is the <b>authoritative</b> one; the
 * first one only avoids pointless work. This only works because rules are pure functions.
 *
 * <p>We use a programmatic {@link TransactionTemplate} instead of {@code @Transactional} because
 * only step 4 must be transactional. Calling a {@code @Transactional} method of the same class from
 * here would bypass Spring's proxy and silently run without a transaction (a classic Spring trap).
 */
@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    private final CardRepository cards;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final List<AuthorizationRule> rules;
    private final FraudScreener fraudScreener;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public AuthorizationService(CardRepository cards,
                                AccountRepository accounts,
                                TransactionRepository transactions,
                                List<AuthorizationRule> rules,
                                FraudScreener fraudScreener,
                                TransactionTemplate transactionTemplate,
                                Clock clock) {
        this.cards = cards;
        this.accounts = accounts;
        this.transactions = transactions;
        this.rules = List.copyOf(rules);
        this.fraudScreener = fraudScreener;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public AuthorizationResult authorize(AuthorizeCommand command) {
        String fingerprint = command.fingerprint();

        // 1. Idempotency fast path: most retries end here without touching cards or accounts.
        Optional<Transaction> previous = transactions.findByIdempotencyKey(command.idempotencyKey());
        if (previous.isPresent()) {
            return replay(previous.get(), fingerprint);
        }

        // 2. Unlocked snapshot + cheap eligibility checks.
        Instant now = Timestamps.now(clock);
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Card card = cards.findById(command.cardId())
                .orElseThrow(() -> new NotFoundException("Card", command.cardId()));
        Account snapshot = loadAccount(card.accountId());
        Optional<DeclineReason> earlyDecline =
                firstFailure(new AuthorizationContext(card, snapshot, command.amount(), today));

        // 3. Fraud screening, only for transactions that are otherwise eligible. No lock, no DB transaction.
        FraudAssessment fraud = earlyDecline.isPresent() ? null : fraudScreener.screen(
                new FraudScreeningRequest(card.id(), card.accountId(), command.amount(), command.merchant(), now));

        // 4. The short, locked, authoritative part.
        try {
            return transactionTemplate.execute(status ->
                    decideAndRecord(command, fingerprint, card, earlyDecline, fraud, today, now));
        } catch (DuplicateKeyException e) {
            // Two requests with the same key raced past step 1. The UNIQUE constraint let exactly
            // one INSERT win and rolled back everything this one did. Return the winner's result.
            Transaction winner = transactions.findByIdempotencyKey(command.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("duplicate key but no row found", e));
            return replay(winner, fingerprint);
        }
    }

    /** Runs inside the DB transaction opened by {@link #authorize}. */
    private AuthorizationResult decideAndRecord(AuthorizeCommand command,
                                                String fingerprint,
                                                Card card,
                                                Optional<DeclineReason> earlyDecline,
                                                FraudAssessment fraud,
                                                LocalDate today,
                                                Instant now) {
        DeclineReason declineReason;
        TransactionStatus status;

        if (earlyDecline.isPresent()) {
            // Decide on what was true when the request arrived. We deliberately do NOT re-evaluate:
            // if the card were unblocked a millisecond later we would approve without a fraud check.
            declineReason = earlyDecline.get();
            status = TransactionStatus.DECLINED;
        } else if (fraud.decision() == FraudDecision.DECLINE) {
            declineReason = DeclineReason.SUSPECTED_FRAUD;
            status = TransactionStatus.DECLINED;
        } else {
            // Lock the account row; concurrent authorizations on this account wait here.
            Account locked = accounts.findByIdForUpdate(card.accountId())
                    .orElseThrow(() -> new IllegalStateException("account vanished: " + card.accountId()));
            // Re-read the card too: it may have been blocked while we were screening.
            Card freshCard = cards.findById(card.id())
                    .orElseThrow(() -> new IllegalStateException("card vanished: " + card.id()));

            declineReason = firstFailure(new AuthorizationContext(freshCard, locked, command.amount(), today))
                    .orElse(null);
            if (declineReason == null) {
                accounts.updateAvailableCredit(locked.reserve(command.amount()));
                status = fraud.decision() == FraudDecision.REVIEW
                        ? TransactionStatus.PENDING_REVIEW
                        : TransactionStatus.APPROVED;
            } else {
                status = TransactionStatus.DECLINED;
            }
        }

        Transaction transaction = new Transaction(
                UUID.randomUUID(), card.id(), card.accountId(), command.amount(), command.merchant(),
                status, declineReason, command.idempotencyKey(), fingerprint, now);
        transactions.insert(transaction);

        // Structured key=value logging: easy to search later in CloudWatch. Never log card data.
        log.info("authorization decided transactionId={} cardId={} status={} declineReason={}",
                transaction.id(), card.id(), status, declineReason);
        return new AuthorizationResult(transaction, false);
    }

    /**
     * Runs the rules in order and returns the first failure ("short-circuit", like {@code &&}).
     * Algorithmically a linear scan: O(r) for r rules, stopping early on the first failure.
     */
    private Optional<DeclineReason> firstFailure(AuthorizationContext context) {
        for (AuthorizationRule rule : rules) {
            // Pattern matching for switch (Java 21): exhaustive because RuleResult is sealed.
            switch (rule.evaluate(context)) {
                case RuleResult.Fail fail -> {
                    return Optional.of(fail.reason());
                }
                case RuleResult.Pass pass -> {
                    // continue with the next rule
                }
            }
        }
        return Optional.empty();
    }

    private AuthorizationResult replay(Transaction previous, String fingerprint) {
        if (!previous.requestFingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyReuseException(previous.idempotencyKey());
        }
        return new AuthorizationResult(previous, true);
    }

    private Account loadAccount(UUID accountId) {
        // A card always references an existing account (foreign key), so absence is a bug, not a 404.
        return accounts.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("card references missing account " + accountId));
    }
}
