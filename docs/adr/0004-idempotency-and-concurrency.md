# 0004: Idempotency keys + pessimistic row locking for authorizations

**Status:** Accepted (Phase 1)

## Context
Two failure modes would cost real money:
1. **Retries:** a client sends a purchase, the network times out before the response arrives, the
   client retries. Without protection, the customer is charged twice.
2. **Races:** two purchases on the same account arrive at the same time. Both read
   "100.00 available", both approve 60.00, the account is overspent (a *lost update*).

## Decision
- `POST /api/v1/transactions` **requires** an `Idempotency-Key` header. The key and a SHA-256
  fingerprint of the request are stored with the transaction under a **UNIQUE constraint**.
  - Same key + same body → return the stored result (`Idempotent-Replayed: true`).
  - Same key + different body → `422 IDEMPOTENCY_KEY_REUSED`.
  - Two in-flight requests with the same key → the UNIQUE constraint lets exactly one INSERT win;
    the other transaction rolls back (including its credit reservation) and returns the winner's result.
- The credit check and reservation run inside a short DB transaction that first takes a
  **row lock** on the account (`SELECT ... FOR UPDATE`) and re-validates on fresh data.
- The fraud call happens **before** that transaction, so no lock is held during network I/O.

Code: `service/AuthorizationService.java`, `infrastructure/persistence/JdbcAccountRepository.java`,
`db/migration/V1__create_core_schema.sql`. Proven by `integration/TransactionApiIT.java`
(`concurrentPurchasesNeverOverspendTheCreditLimit`, `retryWithSameIdempotencyKeyDoesNotChargeTwice`).

## Alternatives considered
- **Optimistic locking** (a `version` column; `UPDATE ... WHERE version = ?`, retry on conflict).
  No waiting, great when conflicts are rare. But a hot account (many purchases per second) causes
  retry storms, and retries need careful idempotent design. Pessimistic is simpler to reason about
  for money, and the lock is held only for a few milliseconds.
- **`SERIALIZABLE` isolation.** Correct, but the whole transaction can fail with serialization
  errors that must be retried; it is broader than needed.
- **Atomic conditional UPDATE** (`UPDATE accounts SET available = available - :x WHERE id = :id AND available >= :x`).
  Elegant and lock-free at the application level; a strong alternative. Rejected for now because
  the rules (Strategy pattern) need the full account state to produce a decline reason.
- **Idempotency in a cache (Redis) with TTL.** Common at scale; adds infrastructure. The database
  is already there and is transactional with the write itself.

## Consequences
- + Verified: removing `FOR UPDATE` makes the concurrency test approve 10 of 10 purchases against
  a limit that allows 5. With the lock, exactly 5.
- − Requests on the *same account* are serialized; throughput per account is bounded by lock hold time.
- − Idempotency keys are kept forever (no expiry yet) and are global, not scoped per client.
  Real systems scope keys per merchant/client and expire them (e.g. after 24h).
