# 0001: Two services: Java for transactions, Python for fraud scoring

**Status:** Accepted (Phase 0)

## Context
The platform has two very different jobs: (1) owning money and card state with strong consistency,
and (2) scoring fraud risk with a machine-learning model that will be retrained and changed often.

## Decision
- **Transaction Service** in Java 21 + Spring Boot 3.5: the system of record for accounts, cards and transactions.
- **Fraud Scoring Service** in Python + FastAPI: stateless, returns a score, a decision and reasons.
- They communicate over REST/JSON. The transaction service treats the fraud score as **advice**:
  it always makes the final decision and owns the fallback if scoring is unavailable (Phase 3).

## Alternatives considered
- **One Java monolith with the model embedded** (e.g. ONNX). Simpler to deploy, but couples model
  releases to payment releases and gives up Python's ML ecosystem.
- **One Python service for everything.** Fine technically, but the target roles (Capital One) use
  Java/Spring for transactional systems, and learning that is a goal of the project.

## Consequences
- + Each service can be deployed, scaled and failed independently; the model can change without touching money code.
- + Each language is used where it is strongest.
- − A network call sits in the authorization path: latency, timeouts and partial failure must be designed for (Phase 3).
- − Two build systems, two test suites, two container images.
- Spring Boot 3.5 was chosen over 4.x because the project brief targets Boot 3 and 3.5 is still
  maintained; upgrading to 4.x later is a good exercise (Jakarta EE 11, Jackson 3).
