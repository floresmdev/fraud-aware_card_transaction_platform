# 0003: Spring JDBC (`JdbcClient`) instead of JPA/Hibernate

**Status:** Accepted (Phase 1)

## Context
Spring projects usually use JPA (Hibernate) for persistence. JPA maps mutable objects to tables and
generates SQL. Our domain objects are immutable records, and the authorization path depends on
precise SQL behavior: `SELECT ... FOR UPDATE`, a UNIQUE constraint as the idempotency referee,
and row-value comparison for keyset pagination.

## Decision
Use Spring's `JdbcClient` with hand-written SQL in `infrastructure/persistence/Jdbc*Repository.java`,
behind domain repository interfaces (ports). Schema is owned by Flyway migrations.

## Alternatives considered
- **Spring Data JPA.** Less boilerplate for CRUD. But entities need no-arg constructors and mutable
  fields (they clash with immutable records), and behavior such as dirty checking, lazy loading,
  flush timing and the N+1 query problem is implicit. For a learning project about consistency, the
  hidden SQL is a liability.
- **Spring Data JDBC.** A middle ground with less magic than JPA; reasonable, but `JdbcClient`
  is simpler to read for the few queries we have.
- **jOOQ.** Type-safe SQL, excellent, but one more tool to learn now.

## Consequences
- + Every query that runs is visible in the code; locking and constraints behave exactly as written.
- + Domain stays framework-free and immutable.
- − Manual row mapping (a few lines per table).
- − JPA is still very common in industry and interviews: I study its core ideas separately
  (entity lifecycle, persistence context, lazy loading, N+1, `@Transactional`) in docs/learning.
