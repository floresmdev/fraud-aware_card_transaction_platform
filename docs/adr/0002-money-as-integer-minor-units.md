# 0002: Money as integer minor units

**Status:** Accepted (Phase 1)

## Context
Binary floating point (`double`, `float`, SQL `REAL`) cannot represent most decimal fractions:
`0.1 + 0.2 = 0.30000000000000004`. In a payments system, these errors accumulate and break
reconciliation to the cent.

## Decision
- Domain type `Money(long minorUnits, Currency currency)`: `transaction-service/src/main/java/dev/floresm/transactions/domain/money/Money.java`.
- Stored as `BIGINT` + `CHAR(3)` currency columns (`V1__create_core_schema.sql`).
- In JSON, amounts are **strings** with the currency's scale: `{"amount": "12.34", "currency": "USD"}`.
- Input with more decimals than the currency allows (`12.345 USD`) is **rejected**, never rounded.
- Arithmetic uses `Math.addExact`/`subtractExact`, which throw on overflow.

## Alternatives considered
- **`BigDecimal` everywhere + `NUMERIC(19,4)` in SQL.** Also correct and common in banks. More flexible
  (sub-cent precision for FX or interest), but slower, and easy to misuse (`equals` considers scale:
  `2.0 != 2.00`; division needs an explicit rounding mode).
- **`double`.** Rejected: incorrect by construction.

## Consequences
- + Exact, fast, trivial to store and compare.
- + The type system prevents adding USD to MXN by accident.
- − Sub-minor-unit precision (interest calculations, FX rates) will need `BigDecimal` at those
  specific points, converting back to minor units with an explicit rounding policy.
- − Currencies without a defined minor unit (e.g. XAU) are not supported.
