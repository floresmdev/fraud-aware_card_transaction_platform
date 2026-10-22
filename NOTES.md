# NOTES

My quick notes while building this project. Short, in my own words, and always with the **file
where it lives**, so I can jump from the idea to the code. Long explanations live in
`docs/learning/` and the decisions in `docs/adr/`.

Paths: `TS/` = `transaction-service/src/main/java/dev/floresm/transactions/`,
`TS-test/` = `transaction-service/src/test/java/dev/floresm/transactions/`.

---

## Codebase map (where things are)

| I'm looking for... | File |
|---|---|
| How money works | `TS/domain/money/Money.java` |
| Account + credit reservation | `TS/domain/account/Account.java` |
| Card, expiry, blocking | `TS/domain/card/Card.java` |
| The rules that can decline a purchase | `TS/domain/authorization/rules/*.java` |
| The rule interface + result type | `TS/domain/authorization/AuthorizationRule.java`, `RuleResult.java` |
| **The core authorization flow** | `TS/service/AuthorizationService.java` |
| Idempotency fingerprint | `TS/service/AuthorizeCommand.java` |
| History + pagination cursor | `TS/service/TransactionHistoryService.java` |
| The SQL | `TS/infrastructure/persistence/Jdbc*Repository.java` |
| The tables | `transaction-service/src/main/resources/db/migration/V1__create_core_schema.sql` |
| Endpoints | `TS/api/*Controller.java` |
| JSON shapes | `TS/api/dto/*.java` |
| Error responses | `TS/api/error/GlobalExceptionHandler.java` |
| Config / env vars | `transaction-service/src/main/resources/application.yml` |
| Fraud placeholder (replaced in Phase 3) | `TS/infrastructure/fraud/ApproveAllFraudScreener.java` |
| Python service | `fraud-scoring-service/app/main.py` |
| CI | `.github/workflows/ci.yml` |
| Local DB | `docker-compose.yml` |

---

## Phase 0: Setup & planning

- **Two services:** the transaction service owns the money (source of truth). The fraud service only
  gives an *opinion*. If it's down, payments still need a decision, so the fallback is designed on
  purpose (Phase 3). → `docs/adr/0001-two-services.md`
- **Maven wrapper (`transaction-service/mvnw`):** pins the Maven version in the repo, so anyone runs
  `./mvnw verify` without installing Maven. Same idea as `uv.lock` in Python.
- **`uv` + `pyproject.toml` + `uv.lock`** (`fraud-scoring-service/`): Python's equivalent of `pom.xml`.
  The lock file pins exact versions → reproducible builds.
- **CI** (`.github/workflows/ci.yml`): every push runs all tests for both services in parallel jobs.
  `permissions: contents: read` = least privilege for the CI token.
- **Secrets:** nothing real in the repo. `application.yml` reads `${DB_PASSWORD:local-dev-only}`; the
  default only works against my local Docker DB. `.gitignore` blocks `.env`, `*.tfvars`, `*.pem`.

### Fundamentals I didn't know at the level check → `docs/learning/00-fundamentals.md`
- Interface = contract (many per class); abstract class = shared state/code (only one parent). Default to interfaces.
- `final` field ≠ immutable object: `final List` can still be `.add()`ed → use `List.copyOf`.
- Override `equals` ⇒ override `hashCode`, or `HashMap.get` silently returns `null`.
- HashMap: O(1) average, O(log n) worst since Java 8 (buckets become red-black trees), amortized O(1) put.
- First duplicate in O(n): `HashSet.add()` returns false if already there. O(n) space.
- ACID. "I" = isolation, which protects from dirty reads, non-repeatable reads, phantoms and **lost updates**. Postgres default is READ COMMITTED, which does NOT stop lost updates by itself.
- An index speeds reads, but costs write time, storage and memory. Composite index: leftmost-prefix rule.
- Docker image = class, container = object. Containers share the host kernel (not VMs).
- `curl localhost:8080`: `/etc/hosts` → 127.0.0.1 → TCP handshake over the loopback interface → plain-text HTTP request → Tomcat thread → DispatcherServlet → my controller.

---

## Phase 1: Java domain + REST API + tests

### Money → `TS/domain/money/Money.java`, ADR 0002
- `long` minor units + `Currency`. 12.34 USD = 1234. JPY has 0 decimals, so 500 JPY = 500.
- `0.1 + 0.2 != 0.3` with doubles. Proven in `TS-test/domain/money/MoneyTest.java`.
- **Reject** `12.345 USD`, never round silently. `Math.addExact` throws on overflow.
- In JSON the amount is a **string** ("12.34") because JavaScript parses numbers as floats. → `TS/api/dto/MoneyDto.java`
- Can't compare USD with MXN → exception. The type system protects me.

### Records & immutability → every file in `TS/domain/`
- `record` = final class + private final fields + constructor + accessors + equals/hashCode/toString.
- The compact constructor validates → an invalid object **can't exist**.
- `Account.reserve()` returns a **new** Account. Nothing mutates. Thread-safe for free. → `TS/domain/account/Account.java`
- Value object (`Money`, `Merchant`) = equal by value. Entity (`Account`, `Card`) = equal by identity (`id`).

### Polymorphism / Strategy → `TS/domain/authorization/`
- `AuthorizationRule` is a one-method interface; 4 classes implement it in `rules/`.
- Spring injects all of them as `List<AuthorizationRule>` sorted by `@Order`.
- New rule = new class. No edits to existing code → **Open/Closed principle**.
- `RuleResult` is a **sealed interface** (Pass | Fail). `switch` over it is exhaustive, no `default` needed. → `RuleResult.java`, used in `AuthorizationService.firstFailure`
- Declines are return values, not exceptions: exceptions are for the unexpected.

### Ports & adapters → `TS/domain/*/…Repository.java` + `TS/infrastructure/persistence/`
- Domain defines the interface (port); JDBC class implements it (adapter). Dependency Inversion.
- Payoff: Mockito mocks in unit tests, real SQL in integration tests.
- Same trick for fraud: `FraudScreener` (port) → `ApproveAllFraudScreener` now, HTTP client in Phase 3.

### Idempotency → `TS/service/AuthorizationService.java`, `TS/service/AuthorizeCommand.java`, ADR 0004
- Client sends `Idempotency-Key`. Same key + same body → stored result (+ `Idempotent-Replayed: true`). Same key + different body → 422.
- Body identity = SHA-256 fingerprint. Fields are **length-prefixed** before hashing so "AB"+"C" ≠ "A"+"BC".
- Two same-key requests at once: the **UNIQUE constraint** lets only one INSERT win; the loser's
  transaction rolls back (credit included) and returns the winner's result. The DB is the referee.

### Concurrency → `TS/infrastructure/persistence/JdbcAccountRepository.java` (`findByIdForUpdate`)
- Bug to prevent: **lost update** (two requests read 100 available, both spend 60).
- Fix: `SELECT ... FOR UPDATE` locks the account row until commit.
- **I measured it:** removing `FOR UPDATE` → 10/10 concurrent 20.00 purchases approved on a 100.00
  limit. With it → exactly 5. Test: `TS-test/integration/TransactionApiIT.java` (`concurrentPurchasesNeverOverspendTheCreditLimit`).
- Flow = cheap checks without lock → fraud call (no lock!) → lock + **re-check** → reserve → insert → commit.
- Never hold a DB lock/connection during a network call (fraud service). It would block every other
  purchase on that account and can drain the connection pool (10 connections).
- `@Transactional` trap: calling it from the same class skips the proxy → no transaction. That's why
  I use `TransactionTemplate` there.

### SQL & schema → `transaction-service/src/main/resources/db/migration/V1__create_core_schema.sql`
- Flyway runs `V1__…sql` once and records it. Never edit an applied migration → add V2.
- CHECK constraints repeat the Java invariants (defense in depth).
- Postgres does **not** auto-index foreign keys → `idx_cards_account_id`.
- Enums stored by **name**, never ordinal. → `JdbcCardRepository.java`
- Postgres `TIMESTAMPTZ` = microseconds, Java `Instant` = nanoseconds → truncate at the source or
  round trips don't match. → `TS/service/Timestamps.java`

### Pagination → `TS/service/TransactionHistoryService.java`, `JdbcTransactionRepository.findByCardBefore`
- OFFSET = O(offset + k), and inserts shift pages (duplicates/gaps).
- Keyset: "give me rows after (created_at, id) of the last row" → O(log n + k) with the composite index.
- `id` is the tie-breaker: the sort key must be unique.
- Fetch `limit + 1` to know if there's a next page without `COUNT(*)`.

### REST & errors → `TS/api/`
- Decline = `201` with `status: DECLINED`, not an HTTP error. 4xx = client's fault, 5xx = ours.
- `POST` isn't idempotent by HTTP spec (GET/PUT/DELETE are) → that's why the header exists.
- Errors follow RFC 9457 Problem Details + my own `code` and `errors[]`. Never leak stack traces. → `GlobalExceptionHandler.java`
- `Idempotency-Key` has `@Pattern` → Spring 6.1 uses *method validation* → `HandlerMethodValidationException` instead of `MethodArgumentNotValidException`. I handle both (found by a failing test).

### Testing → `transaction-service/src/test/`
- Unit (no Spring, ms): `MoneyTest`, `AccountTest`, `CardTest`, `AuthorizationRulesTest`.
- Mockito (`TS-test/service/AuthorizationServiceTest.java`): `when().thenReturn()`, `verify(never())`, `ArgumentCaptor`, `verifyNoInteractions`.
- Integration (`*IT.java`, `./mvnw verify`): real Postgres in Docker via Testcontainers. Not H2: test what you run.
- A test must be able to fail. I broke the lock on purpose to check the concurrency test catches it.

### Interview one-liners
- "Money?" → integer minor units or BigDecimal, never double; reject extra precision.
- "Two withdrawals at once?" → row lock (or optimistic version / conditional update) + DB constraint as backstop.
- "Retry safety?" → idempotency key + unique constraint + atomic transaction.
- "Polymorphism in your project?" → `AuthorizationRule` strategies.

### Open questions for myself
- Fail open or fail closed when fraud scoring is down? Probably depends on the amount → Phase 3 ADR.
- What should `PENDING_REVIEW` mean at the point of sale? → Phase 3 ADR.
