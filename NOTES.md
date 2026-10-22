# NOTES

Quick notes I keep while building this project. Short, in my own words, focused on what I need to remember for interviews (Capital One TDP: CodeSignal + Power Day).

---

## Phase 0: Setup & planning

### Why this project is shaped the way it is
- **Two services, not one:** the transaction service owns the money and the source of truth. The fraud service only gives an *opinion*. If the fraud service goes down, payments still have to make a decision, so I have to design the fallback on purpose.
- **Java for the money side, Python for the ML side:** that's what banks actually do. Java gives strong typing and a mature ecosystem for transactional systems; Python has the ML tooling.

### Concepts to remember
- **Never use `double` / `float` for money.** `0.1 + 0.2 != 0.3` in binary floating point. Use `BigDecimal` or integer cents (`long`), and always keep the currency next to the amount.
- **Idempotency:** doing the same request twice has the same effect as doing it once. A client that times out and retries a payment must not charge the customer twice. Solution: the client sends an `Idempotency-Key` header, the server stores the key with the result and returns the stored result on a repeat.
- **Accuracy is a trap with imbalanced data.** In the Kaggle dataset ~0.17% of transactions are fraud, so a model that says "never fraud" is ~99.8% accurate and completely useless. Use precision, recall and PR-AUC instead.
  - Precision = of what I flagged, how much was actually fraud. (Low precision → I annoy good customers.)
  - Recall = of all fraud, how much I caught. (Low recall → I lose money.)
- **The threshold is a business decision, not a model decision.** The model gives a score; *where I cut* depends on the cost of a false positive vs. a false negative.

### Interview angle
- "Design classes for a banking system" → think `Account`, `Card`, `Transaction`, `Money` (value object), and an interface like `FraudRule` with several implementations (polymorphism).
- "What happens if a downstream service is slow?" → timeout, fallback, and I must justify the fallback in business terms.

### Open questions for myself
- Fail open (approve) or fail closed (decline) when fraud scoring is down? Probably depends on the amount. Decide in Phase 3 and write an ADR.
