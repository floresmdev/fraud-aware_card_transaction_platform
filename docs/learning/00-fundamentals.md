# 00: Fundamentals (answers to the Phase 0 level check)

These are the 12 level-check questions, answered in depth. Each answer ends with **where it
appears in this codebase**, so the theory is tied to real code. Re-read this before the CodeSignal
assessment and the Power Day.

---

## 1. Interface vs. abstract class

| | `interface` | `abstract class` |
|---|---|---|
| Purpose | A **contract**: *what* something can do | A **partial implementation**: shared *how* |
| State (fields) | Only `static final` constants | Any fields, including mutable instance state |
| Constructors | No | Yes (called by subclasses via `super(...)`) |
| Methods | Abstract, plus `default` / `static` / `private` methods (Java 8+/9+) | Abstract and concrete, any visibility |
| Inheritance | A class can implement **many** | A class can extend only **one** |

**When to use which**
- Use an **interface** when unrelated classes should be interchangeable behind one capability.
  This is the default choice. "Program to an interface, not an implementation."
- Use an **abstract class** when several subclasses share real state or code and are genuinely
  "kinds of" the same thing (template method pattern: the base class defines the algorithm's
  skeleton, subclasses fill in steps).
- Rule of thumb: start with an interface. Introduce an abstract base class only if duplication
  between implementations appears.

**Why Java has single inheritance of classes:** to avoid the "diamond problem" (two parents with
conflicting state/implementations). Interfaces with `default` methods can still conflict; Java then
forces the class to override and pick explicitly (`A.super.method()`).

**In this codebase:** `AuthorizationRule`, `FraudScreener`, `AccountRepository` are interfaces with
several (or swappable) implementations. There is no abstract class yet because the rules share no
state. That is a deliberate choice you can defend.
- `transaction-service/src/main/java/dev/floresm/transactions/domain/authorization/AuthorizationRule.java`
- `transaction-service/src/main/java/dev/floresm/transactions/domain/fraud/FraudScreener.java`

---

## 2. `final` on a variable, a method, a class

- **Variable / field:** can be assigned **once**. For a reference, the *reference* cannot change,
  but the object it points to can still be mutated (`final List<String> xs` → `xs.add()` works).
  True immutability needs final fields **and** immutable contents (hence `List.copyOf`).
- **Method:** cannot be **overridden** by subclasses. Use it to protect an invariant-critical step.
- **Class:** cannot be **extended** (e.g. `String`, every `record`). Prevents subclasses from
  breaking invariants or `equals` contracts.

Why it matters: immutable objects are automatically thread-safe (no thread can see them half-changed),
can be shared and cached freely, and make reasoning local.

**In this codebase:** all domain types are `record`s (implicitly final class with final fields).
`FraudAssessment` does `reasons = List.copyOf(reasons)` because a final field holding a mutable
list would still be mutable from outside.
- `.../domain/fraud/FraudAssessment.java`, `.../domain/money/Money.java`
- Services have `private final` dependencies: `.../service/AuthorizationService.java`

---

## 3. `equals()` and `hashCode()`, and what breaks in a `HashMap`

**The contract:** if `a.equals(b)` then `a.hashCode() == b.hashCode()`. (The reverse is not
required: different objects may share a hash, which is a *collision*.)

**How a `HashMap` works (Java 8+):**
1. It holds an array of **buckets** (default capacity 16).
2. `put(key, value)`: compute `key.hashCode()`, spread the bits, take `hash & (capacity - 1)` → bucket index.
3. Inside the bucket, entries form a linked list; if a bucket grows beyond 8 entries (and the table is ≥ 64), it becomes a **red-black tree**.
4. `get(key)`: go to the bucket from the hash, then compare with `equals()` to find the exact key.
5. When size exceeds `capacity × loadFactor (0.75)`, the table **doubles** and every entry is rehashed.

**If you override `equals` but not `hashCode`:** two "equal" objects get different identity hash
codes, land in different buckets, and `map.get(equalKey)` returns `null`. A `HashSet` will happily
store "duplicates". The bug is silent.

**Also dangerous:** using a *mutable* object as a key and mutating it after insertion. Its hash
changes, so it is now in the "wrong" bucket and becomes unreachable.

**In this codebase:** records generate `equals`/`hashCode` from all components, so `Money` works as
a value. See `MoneyTest.valueObjectsWithTheSameValueAreEqual`. The pagination IT uses a
`HashSet<String>` to detect duplicate IDs in O(1) per check.
- `transaction-service/src/test/java/dev/floresm/transactions/domain/money/MoneyTest.java`
- `transaction-service/src/test/java/dev/floresm/transactions/integration/TransactionApiIT.java`

---

## 4. Hash map lookup complexity

- **Average:** O(1). The hash sends you straight to a bucket holding ~1 entry.
- **Worst case:** all keys collide into the same bucket (bad `hashCode`, or adversarial input). It
  was O(n) with a linked list; since Java 8 it is **O(log n)** because of treeification (if keys are
  `Comparable`).
- **Resize:** an individual `put` that triggers a resize is O(n), but resizes are rare (doubling),
  so `put` is **amortized O(1)**. This is the same argument as `ArrayList.add`.

Interview follow-up: "When would you use a `TreeMap`?" → when you need keys **sorted** or range
queries (`floorKey`, `headMap`): O(log n) for everything, red-black tree.

---

## 5. Find the first duplicate card number in O(n)

```java
// Time O(n): one pass, each HashSet.add is O(1) on average.
// Space O(n): in the worst case (no duplicates) the set holds every element.
static Optional<String> firstDuplicate(List<String> cardNumbers) {
    Set<String> seen = new HashSet<>();
    for (String number : cardNumbers) {
        if (!seen.add(number)) {      // add() returns false if it was already present
            return Optional.of(number);
        }
    }
    return Optional.empty();
}
```
Alternatives to mention: sort first then compare neighbours → O(n log n) time, O(1) extra space (if
in-place), but "first" then means first in sorted order, not in input order. Nested loops → O(n²).
Talking through that trade-off (time vs. space vs. semantics) is what the interviewer wants.

---

## 6. Database transactions and ACID; what does "I" protect you from?

A **transaction** groups several SQL statements into one all-or-nothing unit.

- **A, Atomicity:** all statements commit, or none do. *Example:* reserving credit and inserting the
  transaction row happen together. If the insert fails, the reservation is rolled back.
- **C, Consistency:** each commit moves the DB from one valid state to another (constraints hold).
  *Example:* `CHECK (available_credit_minor BETWEEN 0 AND credit_limit_minor)`.
- **I, Isolation:** concurrent transactions do not see each other's half-done work, and the result
  is as if they had run in *some* order. It protects you from anomalies:
  - *dirty read:* reading uncommitted data (PostgreSQL never allows this),
  - *non-repeatable read:* the same row read twice gives different values,
  - *phantom read:* the same query returns new rows,
  - *lost update:* two transactions read the same value, both write, and one write is lost.
- **D, Durability:** once committed, it survives a crash (write-ahead log, WAL).

**Isolation levels** trade safety for concurrency: READ UNCOMMITTED < READ COMMITTED (PostgreSQL
default) < REPEATABLE READ < SERIALIZABLE. READ COMMITTED does **not** prevent lost updates by itself.

**Payments example (this project):** two purchases of 20.00 on a 100.00 account at the same time.
At READ COMMITTED without a lock, 10 concurrent requests all read 100.00 and all approve: a lost
update. We fix it with `SELECT ... FOR UPDATE` (row lock). This was **measured** by removing the
lock: 10/10 approved instead of 5/10.
- `.../infrastructure/persistence/JdbcAccountRepository.java` (`findByIdForUpdate`)
- `.../service/AuthorizationService.java`
- `docs/adr/0004-idempotency-and-concurrency.md`

---

## 7. What does an index cost you?

An index (by default a **B-tree**, a balanced tree of sorted keys) turns lookups from a full scan
O(n) into O(log n). Costs:
- **Writes get slower:** every INSERT/UPDATE/DELETE must also update every index on the table.
- **Storage:** an index is a separate on-disk structure, sometimes as big as the table.
- **Memory pressure:** indexes compete for the buffer cache.
- **Not always used:** low-selectivity columns (e.g. `status` with 3 values) often do not benefit;
  the planner may prefer a sequential scan.

Composite index column order matters: `(card_id, created_at DESC, id DESC)` serves
`WHERE card_id = ? ORDER BY created_at DESC, id DESC` but **not** `WHERE created_at > ?` alone
(leftmost-prefix rule).
- `transaction-service/src/main/resources/db/migration/V1__create_core_schema.sql`

---

## 8. Train/test split (preview of Phase 2)

You train a model on one part of the data and measure it on data it has **never seen**. Measuring
on training data rewards memorization (*overfitting*) and gives a falsely high score. For fraud:
- Use a **stratified** split so the tiny fraud class (~0.17%) appears in both parts in the same proportion.
- Prefer a **time-based** split when possible (train on the past, test on the future), because
  that is how the model will be used, and fraud patterns drift.
- Choose the decision threshold on a **validation** set, and report final numbers on a test set
  touched only once. Otherwise you are tuning to the test set (*data leakage*).

---

## 9. Docker image vs. container

- **Image:** a read-only, layered filesystem snapshot + metadata (default command, env, ports).
  Built from a `Dockerfile`; each instruction creates a cached **layer**. Like a **class**.
- **Container:** a running (or stopped) instance of an image, with a thin writable layer on top,
  its own process namespace, network namespace and cgroup limits (Linux kernel features). Like an
  **object**. Many containers can run from one image.
- Containers are **not** VMs: they share the host kernel, so they start in milliseconds.
- Data written inside a container is lost when it is removed; use **volumes** for persistence
  (see `pgdata` in `docker-compose.yml`).

The Integration tests already use Docker: Testcontainers starts a `postgres:16-alpine` container,
runs the tests, and removes it. Dockerfiles for both services come in Phase 3.

---

## 10. What happens between `curl http://localhost:8080/...` and the app?

curl does not "download a page" in a magic way; it opens a TCP connection, writes an HTTP request as
text, and reads the HTTP response. Layer by layer:

1. **URL parsing:** scheme `http` (so plain HTTP, default port 80, but here explicit `8080`), host
   `localhost`, path `/api/v1/accounts`.
2. **Name resolution:** `localhost` is resolved via `/etc/hosts` (by `nsswitch.conf` order), giving
   `127.0.0.1` (IPv4) and/or `::1` (IPv6). No DNS server is contacted.
3. **Socket + TCP 3-way handshake:** curl asks the kernel for a socket and connects to
   `127.0.0.1:8080`. SYN → SYN-ACK → ACK. The source port is a random *ephemeral* port.
4. **Loopback interface (`lo`):** packets never reach a NIC or the network; the kernel routes them
   internally (layer 3), straight to the socket that is **listening** on port 8080, owned by the
   Java process (embedded Tomcat). If nothing listens: `Connection refused` (the kernel answers RST).
5. **HTTP request (layer 7)**, plain text over the TCP stream:
   ```
   POST /api/v1/accounts HTTP/1.1
   Host: localhost:8080
   Content-Type: application/json
   Content-Length: 87

   {"holderName": ...}
   ```
6. **Inside the app:** Tomcat accepts the connection, a worker thread parses the request → Spring's
   `DispatcherServlet` finds the `@PostMapping` method → Jackson converts JSON to the DTO → Bean
   Validation runs → controller → service → repository → SQL over *another* TCP connection (from
   the HikariCP pool) to PostgreSQL on port 5432 → result back up → Jackson writes JSON.
7. **HTTP response:** `HTTP/1.1 201`, headers (`Location`, `Content-Type`), body. curl prints it.
8. **Connection close or keep-alive** (HTTP/1.1 reuses connections by default; FIN/ACK when closed).

With **HTTPS**, a TLS handshake (certificate verification, key exchange) happens between steps 3 and 5,
and everything after is encrypted. In AWS (Phase 4), a load balancer terminates TLS in front of the containers.

Try it: `curl -v http://localhost:8080/actuator/health` shows the request and response headers, and
`ss -ltnp | grep 8080` shows the listening socket.

---

## 11–12. Toolchain and time budget
Install list: see the README "Requirements" section. Time budget: tell me your weekly hours so phases
can be sized to finish before the January 2027 application.
