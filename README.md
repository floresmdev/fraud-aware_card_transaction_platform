# Fraud-Aware Card Transaction Platform

A small, production-style **card payments backend** that authorizes card transactions in real time and asks a separate **fraud scoring service** whether each one looks risky before money moves.

It is built as two services that talk over REST, deployed to AWS with Terraform and CI/CD. The point is to practice how real fintech systems are designed: correct money handling, idempotent APIs, clear failure behavior between services, and fraud decisions that are justified by **business cost**, not just model accuracy.

> **Status:** Phase 0 (setup & planning). Nothing below is implemented yet unless the roadmap marks it done. The Results section only ever reports numbers that were actually measured.

---

## The problem (for a product manager)

Every card payment forces a decision in a few hundred milliseconds: **approve, send to review, or decline**.

- If we **decline a legitimate customer** (false positive), we lose the sale, may trigger a support call, and risk the customer leaving.
- If we **approve a fraudulent payment** (false negative), we usually absorb the fraud loss and a chargeback fee.

These two mistakes have very different costs, so "the most accurate model" is not the goal. The goal is the **decision policy with the lowest expected cost**, explained in plain language and with a sensible answer to "what happens when the fraud system is down?".

## What the system does (for an engineer)

| Service | Stack | Responsibility |
|---|---|---|
| **Transaction Service** | Java 21, Spring Boot 3, PostgreSQL, Flyway | Accounts, cards, transaction authorization, transaction history. Owns the money and the source of truth. |
| **Fraud Scoring Service** | Python, FastAPI, scikit-learn | Returns a fraud score, a decision (`APPROVE` / `REVIEW` / `DECLINE`) and human-readable reasons. Combines an ML model with an explainable rules layer. |

```
          ┌──────────────┐  POST /transactions (Idempotency-Key)
 client ─▶│ Transaction  │─────────────────────────────┐
          │ Service      │                             │
          │ (Spring Boot)│── POST /score (timeout) ──▶ ┌▼──────────────┐
          └──────┬───────┘◀── score + decision ─────── │ Fraud Scoring │
                 │                                     │ (FastAPI)     │
                 ▼                                     │ model + rules │
          ┌──────────────┐                             └───────────────┘
          │ PostgreSQL   │
          │ (Flyway)     │
          └──────────────┘
```

*(A proper architecture diagram, including the AWS deployment, will replace this sketch in Phase 4.)*

### Key engineering decisions (planned)

- **Money is never a floating-point number.** Amounts are stored as integer minor units (cents) or `BigDecimal`, with an explicit ISO-4217 currency.
- **Idempotency keys** on transaction creation, so a client retry after a network timeout never charges a customer twice.
- **Layered design:** controller → service → domain → repository. Domain objects are immutable where possible; fraud rules are pluggable through a common interface (polymorphism).
- **Explicit error model** (consistent JSON error bodies, correct HTTP status codes) and **OpenAPI** documentation.
- **Timeout + fallback** when the scoring service is slow or down. The fallback is a business decision and will be documented in an ADR.
- **Threshold chosen by a cost matrix**, evaluated with precision, recall and PR-AUC (accuracy is misleading when ~0.17% of transactions are fraud).

Each significant decision will get an Architecture Decision Record (ADR) in `docs/adr/`.

## Repository layout (planned)

```
transaction-service/     Java 21 + Spring Boot 3
fraud-scoring-service/   Python + FastAPI + model training / evaluation
infra/                   Terraform (AWS)
docs/adr/                Architecture Decision Records
docker-compose.yml       Local environment
NOTES.md                 My study notes as the project progresses
```

## Roadmap

Each phase has a Definition of Done (DoD). A phase is only closed when its DoD is met.

- [ ] **Phase 0 — Setup & level check.** Toolchain installed (JDK 21, Maven, Python 3.12, Docker), repo skeleton, conventions, skill baseline. *DoD: both service skeletons build and run "hello" endpoints locally; CI runs on every push.*
- [ ] **Phase 1 — Java domain + REST API + tests.** Accounts, cards, authorization, history, idempotency, validation, error model, OpenAPI. *DoD: unit tests (JUnit 5 + Mockito) and integration tests (Testcontainers + real PostgreSQL) green in CI.*
- [ ] **Phase 2 — Fraud scoring service + model evaluation.** Training on the Kaggle Credit Card Fraud dataset with class-imbalance handling, rules layer, FastAPI endpoint. *DoD: pytest green; precision / recall / PR-AUC reported on a held-out set.*
- [ ] **Phase 3 — Integration + docker-compose.** Service-to-service call with timeout and fallback, structured logs, health checks, basic metrics. *DoD: `docker compose up` runs the whole system; failure of the scoring service is handled as documented.*
- [ ] **Phase 4 — AWS + Terraform + CI/CD.** ECR, ECS Fargate (or Lambda), RDS, least-privilege IAM, AWS Budgets alarm, GitHub Actions deploy, dependency scanning. *DoD: one command deploys, one command (`terraform destroy`) removes everything; costs documented.*
- [ ] **Phase 5 — Business analysis + polish.** Cost matrix, cost-minimizing threshold, results, limitations, next steps. *DoD: this README answers "why" for every design choice, for both engineers and PMs.*

## Results

*Not yet available.* This section will contain only measured results (model metrics on a held-out set, chosen threshold, expected-cost comparison, latency), with the method used to obtain them.

## Limitations

To be documented honestly as the project progresses (e.g., the public dataset uses anonymized PCA features, so the rules layer and the model see different kinds of signals).

## Running locally

Instructions will be added in Phase 0/3.

## License

[MIT](LICENSE)
