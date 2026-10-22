# Architecture Decision Records

An ADR is a short document recording **one** significant decision: the context, what was decided,
what alternatives were rejected and what it costs us. They are never edited after being accepted;
if a decision changes, a new ADR supersedes the old one. This gives future readers (and interviewers)
the *why*, not only the *what*.

| # | Decision | Status |
|---|---|---|
| [0001](0001-two-services.md) | Two services: Java for transactions, Python for fraud scoring | Accepted |
| [0002](0002-money-as-integer-minor-units.md) | Money as integer minor units (BIGINT cents) | Accepted |
| [0003](0003-spring-jdbc-instead-of-jpa.md) | Spring JDBC (`JdbcClient`) instead of JPA/Hibernate | Accepted |
| [0004](0004-idempotency-and-concurrency.md) | Idempotency keys + pessimistic row locking for authorizations | Accepted |

Template: Context → Decision → Alternatives considered → Consequences.
