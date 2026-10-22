# 01: Transaction Service deep dive (Phase 1)

Goal of this guide: be able to explain every file of `transaction-service/` and defend every design
choice in an interview, **without** looking at the code.

All paths below are relative to `transaction-service/src/main/java/dev/floresm/transactions/`
unless they start with `transaction-service/`.

---

## 1. The map: layers and dependencies

```
api/              HTTP in/out. Controllers, DTOs, error handler.        (knows: service, domain)
service/          Use cases. Orchestrates domain + repositories.       (knows: domain)
domain/           Business types and rules. Pure Java (+ @Component).  (knows: nothing above)
infrastructure/   Adapters: SQL repositories, fraud screener stub.     (implements domain ports)
config/           Spring beans (Clock).
```

**The dependency rule:** arrows point *inward*. `domain` never imports from `api`, `service` or
`infrastructure`. The domain declares **ports** (interfaces such as `AccountRepository`), and
`infrastructure` provides **adapters** that implement them. This is called *Hexagonal Architecture*
(or *Ports and Adapters*) and is the Dependency Inversion Principle applied to a whole application.

| Layer | Files | One-line responsibility |
|---|---|---|
| Entry point | `TransactionServiceApplication.java` | Boots Spring, scans for components |
| Config | `config/TimeConfig.java` | Provides a `Clock` bean so time is injectable |
| Domain: money | `domain/money/Money.java`, `InvalidAmountException.java` | Exact money value object |
| Domain: account | `domain/account/Account.java`, `AccountRepository.java` | Credit limit, available credit, reserve |
| Domain: card | `domain/card/Card.java`, `CardStatus.java`, `CardRepository.java` | Card lifecycle and expiry |
| Domain: transaction | `domain/transaction/*.java` | Transaction record, status, decline reasons, merchant |
| Domain: rules | `domain/authorization/*.java`, `rules/*.java` | Pluggable eligibility rules (Strategy) |
| Domain: fraud | `domain/fraud/*.java` | Port to the fraud scorer + its result types |
| Domain: errors | `domain/exception/*.java` | Not found, idempotency key reuse |
| Services | `service/AccountService.java` | Open account, issue/block card |
| | `service/AuthorizationService.java` | **The core**: idempotent, race-safe authorization |
| | `service/TransactionHistoryService.java` | Get transaction, keyset-paginated history |
| | `service/AuthorizeCommand.java`, `AuthorizationResult.java`, `Timestamps.java` | Use-case input/output, time helper |
| Infrastructure | `infrastructure/persistence/Jdbc*Repository.java`, `SqlTime.java` | SQL via `JdbcClient` |
| | `infrastructure/fraud/ApproveAllFraudScreener.java` | Phase 1 placeholder (Null Object) |
| API | `api/*Controller.java` | REST endpoints |
| | `api/dto/*.java` | JSON contract (request/response records) |
| | `api/error/GlobalExceptionHandler.java` | Exceptions → RFC 9457 Problem Details |
| Schema | `transaction-service/src/main/resources/db/migration/V1__create_core_schema.sql` | Tables, constraints, indexes |
| Config | `transaction-service/src/main/resources/application.yml` | Datasource, Actuator, Jackson |

---

## 2. Follow one request end to end: `POST /api/v1/transactions`

Read the files in this order with this guide next to you.

1. **`api/TransactionController.java` → `authorize(...)`**
   Spring maps the URL + verb. `@RequestHeader("Idempotency-Key")` with `@Pattern` validates the
   key. `@Valid @RequestBody AuthorizeTransactionRequest` makes Jackson parse JSON into the record
   and Bean Validation check `@NotNull`, `@Pattern`, `@Positive` (declared in `api/dto/*.java`).
   Invalid → exception → `GlobalExceptionHandler` → 400 with every bad field listed.
2. **DTO → domain:** `MoneyDto.toDomain()` calls `Money.of(BigDecimal, "USD")`, which rejects
   `1.005 USD`. `MerchantDto.toDomain()` builds a `Merchant`. Both go into an `AuthorizeCommand`.
3. **`service/AuthorizationService.java` → `authorize(command)`**
   - Fingerprint the request (SHA-256, `AuthorizeCommand.fingerprint()`).
   - Step 1: `transactions.findByIdempotencyKey`. Found → `replay()`: same fingerprint → return it;
     different → `IdempotencyKeyReuseException` → 422.
   - Step 2: load card and account **without locks**, build an `AuthorizationContext`, run
     `firstFailure(...)` over the `List<AuthorizationRule>`.
   - Step 3: if eligible, call `fraudScreener.screen(...)` (no DB transaction open).
   - Step 4: `transactionTemplate.execute(...)` → `decideAndRecord(...)`: lock the account
     (`findByIdForUpdate` → `SELECT ... FOR UPDATE`), re-read the card, re-run the rules on fresh
     data, `account.reserve(amount)` → `updateAvailableCredit`, `transactions.insert`. Commit
     releases the lock.
   - `DuplicateKeyException` (two requests, same key, both past step 1) → re-read the winner → replay.
4. **`infrastructure/persistence/JdbcTransactionRepository.java` → `insert`**: plain SQL with
   named bind parameters. The `uq_transactions_idempotency_key` constraint is the final referee.
5. **Back in the controller:** `201 Created`, `Location: /api/v1/transactions/{id}`,
   `Idempotent-Replayed: true|false`, body = `TransactionResponse.from(transaction)`.

> **Why is a decline a 201 and not a 4xx?** The request was valid and processed; "declined" is a
> business result. 4xx means the *client* made a mistake, 5xx means *we* failed. Monitoring and
> client retry logic depend on that distinction.

---

## 3. OOP concepts used (with where to find them)

| Concept | What it means | Where | Interview phrasing |
|---|---|---|---|
| **Encapsulation** | Hide state; expose behavior that keeps invariants | `Account.reserve()` is the only way to lower available credit | "How do you prevent invalid states?" |
| **Immutability** | Objects never change after construction | All `record`s; `Account.reserve` returns a new Account | "Why immutable? Thread safety, no aliasing bugs" |
| **Value object** | Equality by value, no identity | `Money`, `Merchant` | "Design a Money class" (very common in banking interviews) |
| **Entity** | Has identity that persists across state changes | `Account`, `Card`, `Transaction` (by `id`) | "Value object vs entity?" |
| **Interface + polymorphism** | One call, many behaviors chosen at runtime | `AuthorizationRule.evaluate()` on 4 classes | "Explain runtime polymorphism / dynamic dispatch" |
| **Strategy pattern** | Family of interchangeable algorithms behind an interface | `domain/authorization/rules/*` | "How would you add a new fraud rule without touching existing code?" |
| **Chain-of-responsibility flavor** | Pass through handlers until one decides | `AuthorizationService.firstFailure` stops at first `Fail` | |
| **Null Object pattern** | A do-nothing implementation instead of `null` | `ApproveAllFraudScreener` | |
| **Factory methods** | Named constructors that encode intent | `Account.open`, `Card.issue`, `Money.of`, `RuleResult.fail` | "Why static factories over constructors?" (names, validation, caching like `RuleResult.PASS`) |
| **Sealed types (sum types)** | Closed hierarchy; exhaustive `switch` | `RuleResult` (Pass / Fail) | "What's new in Java 17/21?" |
| **Parameter / command objects** | Bundle arguments into one type | `AuthorizationContext`, `AuthorizeCommand` | |
| **Dependency Injection** | Objects receive collaborators instead of creating them | Every constructor in `service/` | "What is IoC? Why constructor injection?" |
| **Dependency Inversion (SOLID D)** | Depend on abstractions | Services use `AccountRepository`, not `JdbcAccountRepository` | |
| **Open/Closed (SOLID O)** | Extend without modifying | New rule = new class with `@Component @Order` | |
| **Single Responsibility (SOLID S)** | One reason to change | Controller (HTTP) vs service (use case) vs repository (SQL) | |
| **Liskov Substitution (SOLID L)** | Any implementation is usable where the interface is expected | Mockito mocks and `Jdbc*Repository` are interchangeable | |
| **Interface Segregation (SOLID I)** | Small, focused interfaces | One-method `AuthorizationRule`, `FraudScreener` | |
| **DTO vs domain model** | API contract separate from internal model | `api/dto/*` vs `domain/*` | "Why not return entities from controllers?" |

**Composition over inheritance:** notice there is **zero** class inheritance in our domain (only
interfaces). `AuthorizationService` *has* a list of rules; it does not *extend* anything. Inheritance
couples subclasses to parent internals ("fragile base class"); composition keeps parts swappable.

---

## 4. Data structures and algorithms used

| Where | Structure / algorithm | Complexity | Why it matters |
|---|---|---|---|
| `uq_transactions_idempotency_key` (SQL) | Unique **B-tree** index | O(log n) lookup and uniqueness check | Idempotency check stays fast with millions of rows |
| `idx_transactions_card_history` | Composite B-tree `(card_id, created_at DESC, id DESC)` | Seek O(log n), then read k rows | History query needs no sort step |
| `TransactionHistoryService` | **Keyset pagination** | O(log n + k) per page vs O(offset + k) for OFFSET | Constant cost per page; no duplicates/gaps under inserts |
| `TransactionHistoryService.history` | "Fetch limit + 1" trick | Saves a `COUNT(*)` (which is O(n)) | Know if a next page exists for free |
| `AuthorizationService.firstFailure` | Linear scan with early exit | O(r) for r rules | Order rules cheapest/most-likely-to-fail first |
| `AuthorizeCommand.fingerprint` | **Cryptographic hash** (SHA-256) + length-prefix encoding | O(size of request) | Detects key reuse; length-prefixing prevents ambiguous concatenation |
| `Card` | `SecureRandom` vs `Random` | n/a | `Random` is a predictable LCG; never use it for security-relevant values |
| `TransactionApiIT` | `HashSet` for duplicate detection | O(1) average per check | Same technique as "find the first duplicate" |
| `TransactionApiIT` concurrency | `CountDownLatch` + thread pool | n/a | Releases all threads at once to maximize contention |
| `Money` | `long` with `Math.addExact` | O(1) | Overflow detection instead of silent wrap-around |

**Why keyset pagination is O(log n + k):** the B-tree is sorted by `(card_id, created_at DESC, id DESC)`.
The DB descends the tree to the first entry after the cursor (O(log n)), then walks leaf pages in
order collecting k rows. With `OFFSET 10000`, it must walk 10,000 entries just to discard them.

---

## 5. Concurrency, consistency and transactions

- **Lost update** is the bug: read-modify-write by two transactions at once. Measured: without the
  lock, 10/10 concurrent 20.00 purchases were approved against a 100.00 limit.
- **Pessimistic locking:** `SELECT ... FOR UPDATE` (`JdbcAccountRepository.findByIdForUpdate`). A
  second transaction blocks on the row until the first commits, then reads the *new* value.
- **Why re-check under the lock:** the unlocked snapshot (step 2) may be stale; the check under the
  lock is the authoritative one. Pattern: *optimistic pre-check, authoritative re-check*.
- **Lock scope:** never do network I/O while holding a lock or DB connection. The fraud call is
  outside the transaction. Otherwise one slow fraud response stalls every purchase on that account
  and can exhaust the connection pool (HikariCP, max 10 connections, see `application.yml`).
- **Atomicity backs idempotency:** if the idempotency INSERT loses the race, the transaction rolls
  back the credit reservation too. No partial state.
- **Defense in depth:** Java invariants (`Account` constructor) + SQL `CHECK` constraints.
- **`@Transactional` pitfall (very common interview question):** Spring implements it with a
  *proxy* around the bean. Calling a `@Transactional` method from another method **of the same
  class** bypasses the proxy, so no transaction starts. That is why `AuthorizationService` uses a
  programmatic `TransactionTemplate` for just step 4.

---

## 6. REST design decisions

- Resources and sub-resources: `/accounts/{id}/cards`, `/cards/{id}/transactions`.
- `201 Created` + `Location` for creations; `200` for reads.
- Actions as sub-resources: `POST /cards/{id}/block` (not a generic PATCH of status).
- Versioning in the path: `/api/v1`.
- Idempotency: GET/PUT/DELETE are idempotent by HTTP semantics, **POST is not**, so we add
  `Idempotency-Key`.
- Errors: RFC 9457 Problem Details with a stable `code` and per-field `errors`.
- Money as strings in JSON (JavaScript numbers are floats).
- OpenAPI generated from code: `/v3/api-docs`, UI at `/swagger-ui.html`.

---

## 7. Testing strategy (the test pyramid)

| Level | Files | Tools | Proves |
|---|---|---|---|
| Unit: domain | `transaction-service/src/test/java/.../domain/**` | JUnit 5, AssertJ, `@ParameterizedTest`, `@Nested` | Money math, invariants, each rule, boundaries (expiry on last day of month) |
| Unit: service | `.../service/AuthorizationServiceTest.java` | Mockito (`@Mock`, `when`, `verify`, `ArgumentCaptor`) | Orchestration: fraud not called on early decline, re-check under lock, replay, race-loser path |
| Integration | `.../integration/TransactionApiIT.java` | Spring Boot test, Testcontainers PostgreSQL, real HTTP | SQL, migration, constraints, row lock under real concurrency, error shapes, OpenAPI, health |

Run: `./mvnw test` (unit, seconds) and `./mvnw verify` (unit + integration, needs Docker).

**The test proves the design.** The concurrency test fails when `FOR UPDATE` is removed. A test that
cannot fail proves nothing. Always check a new test fails for the right reason.

---

## 8. Spring concepts to be able to explain

- **IoC container / beans:** Spring creates objects (beans) and wires them. Component scanning finds
  `@Component`, `@Service`, `@Repository`, `@RestController`.
- **Constructor injection:** dependencies are `final`, explicit, and testable with `new`.
- **Injecting a `List<Interface>`:** Spring injects *all* beans implementing `AuthorizationRule`,
  ordered by `@Order`.
- **Auto-configuration:** adding `spring-boot-starter-jdbc` + a datasource URL creates a
  `DataSource` (HikariCP), `JdbcClient`, `TransactionManager` and `TransactionTemplate` for you.
- **Profiles / externalized config:** `${DB_URL:default}` in `application.yml` reads env vars.
- **Actuator:** `/actuator/health` (+ liveness/readiness probes) for load balancers and ECS.
- **Flyway:** applies `V1__*.sql` on startup and records it in `flyway_schema_history`.

---

## 9. Practice: do these yourself (no AI)

These are deliberately left for you. Each one is a realistic interview "modify existing code" task.

1. **New rule (OOP, Open/Closed):** add `MaxSingleTransactionAmountRule` that declines any single
   transaction above 10,000.00 in the account currency with a new `DeclineReason.AMOUNT_LIMIT_EXCEEDED`.
   You will touch: a new class in `domain/authorization/rules/`, `DeclineReason.java`, a new unit
   test, and **nothing** in `AuthorizationService`. Then explain why that is the point.
2. **Refund (design):** sketch (on paper) how a refund would work: is it a new row or an update?
   What happens to available credit? What makes it idempotent?
3. **Optimistic locking (concurrency):** describe what would change to use a `version` column
   instead of `FOR UPDATE`, and how the concurrency test's numbers might change.
4. **SQL:** write by hand the query "total approved amount per merchant country for a card in the
   last 30 days". Which index would help?
5. **Explain out loud in 2 minutes:** "What happens when the same payment request is sent twice at
   the same time?" Use the words idempotency key, UNIQUE constraint, rollback, atomicity.

---

## 10. Likely interview questions this phase prepares you for

- Why not `double` for money? What would you use instead and why?
- Design a `Money` class. How do you handle currencies? Overflow?
- Interface vs abstract class? Give an example from a project.
- What is polymorphism? Show me where you used it.
- What is idempotency? How did you implement it? What if two retries arrive at once?
- How do you prevent two concurrent withdrawals from overdrawing an account?
- Optimistic vs pessimistic locking: trade-offs?
- What is a database index? What does it cost? Why that column order?
- OFFSET vs keyset pagination?
- How do you structure a Spring Boot application? Why separate DTOs from domain objects?
- How do you test code that depends on a database? Why not H2?
- What does `@Transactional` do, and when does it silently not work?
