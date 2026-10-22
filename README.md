# Fraud-Aware Card Transaction Platform

A small, production-style **card payments backend** that authorizes card transactions in real time and asks a separate **fraud scoring service** whether each one looks risky before money moves.

It is built as two services that talk over REST, deployed to AWS with Terraform and CI/CD. The point is to practice how real fintech systems are designed: correct money handling, idempotent APIs, clear failure behavior between services, and fraud decisions that are justified by **business cost**, not just model accuracy.

> **Status:** Phase 1 complete (Transaction Service). Phase 2 (fraud model) is next. Only what the roadmap marks as done is implemented. The Results section only ever reports numbers that were actually measured.

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

### Key engineering decisions

- **Money is never a floating-point number.** `Money(long minorUnits, Currency)`; BIGINT in PostgreSQL; strings in JSON. Extra decimals are rejected, never rounded. ([ADR 0002](docs/adr/0002-money-as-integer-minor-units.md))
- **Idempotency keys** on `POST /transactions` (required header). A retry returns the stored result; a reused key with a different body is rejected. A UNIQUE constraint is the final referee. ([ADR 0004](docs/adr/0004-idempotency-and-concurrency.md))
- **No overspending under concurrency.** Credit is reserved under a row lock (`SELECT ... FOR UPDATE`) and re-validated on fresh data. The fraud call happens *before* the lock, so no lock is held during network I/O.
- **Layered, ports-and-adapters design:** controller → service → domain ← infrastructure. Immutable domain records; authorization rules are pluggable strategies behind one interface (polymorphism).
- **Plain SQL with Spring's `JdbcClient`**, not JPA, so locking and constraints behave exactly as written. ([ADR 0003](docs/adr/0003-spring-jdbc-instead-of-jpa.md))
- **Explicit error model** (RFC 9457 Problem Details with stable error codes) and **OpenAPI** docs generated from code.
- *(Phase 3)* **Timeout + fallback** when the scoring service is slow or down, justified as a business decision.
- *(Phase 5)* **Threshold chosen by a cost matrix**, evaluated with precision, recall and PR-AUC (accuracy is misleading when ~0.17% of transactions are fraud).

All decisions: [`docs/adr/`](docs/adr/). Study guides: [`docs/learning/`](docs/learning/). My notes: [`NOTES.md`](NOTES.md).

## Repository layout

```
transaction-service/     Java 21 + Spring Boot 3.5 (Maven wrapper, Flyway, JdbcClient)
  src/main/java/dev/floresm/transactions/
    api/                 REST controllers, DTOs, error handler
    service/             Use cases (authorization, history, accounts)
    domain/              Money, Account, Card, Transaction, rules, ports
    infrastructure/      SQL repositories, fraud screener adapter
  src/main/resources/db/migration/   Flyway SQL migrations
  src/test/java/...      Unit tests (*Test) and integration tests (*IT)
fraud-scoring-service/   Python + FastAPI (skeleton; model in Phase 2)
infra/                   Terraform for AWS (Phase 4)
docs/adr/                Architecture Decision Records
docs/learning/           In-depth study guides per phase
docker-compose.yml       Local environment (PostgreSQL for now)
.github/workflows/ci.yml CI: build + all tests on every push
NOTES.md                 My study notes, with file references
```

## API (Transaction Service)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/accounts` | Open a credit account `{holderName, creditLimit: {amount, currency}}` |
| `GET` | `/api/v1/accounts/{id}` | Account with available credit |
| `POST` | `/api/v1/accounts/{id}/cards` | Issue a card |
| `GET` | `/api/v1/cards/{id}` | Card details (last 4 digits only, never a full card number) |
| `POST` | `/api/v1/cards/{id}/block` | Block a card |
| `GET` | `/api/v1/cards/{id}/transactions?limit=&cursor=` | History, newest first, cursor-paginated |
| `POST` | `/api/v1/transactions` | Authorize a purchase. **Requires `Idempotency-Key` header** |
| `GET` | `/api/v1/transactions/{id}` | One transaction |
| `GET` | `/actuator/health` | Health (plus `/liveness`, `/readiness`) |
| `GET` | `/swagger-ui.html`, `/v3/api-docs` | Interactive docs / OpenAPI spec |

A decline is **not** an HTTP error: it returns `201` with `"status": "DECLINED"` and a `declineReason`
(`CARD_NOT_ACTIVE`, `CARD_EXPIRED`, `CURRENCY_MISMATCH`, `INSUFFICIENT_CREDIT`, `SUSPECTED_FRAUD`).

## Roadmap

Each phase has a Definition of Done (DoD). A phase is only closed when its DoD is met.

- [x] **Phase 0: Setup & level check.** Repo skeleton, conventions, ADRs, CI, both service skeletons with health endpoints. *DoD: both services build and answer a health endpoint; CI runs on every push.* (Local toolchain install on my machine: see Requirements.)
- [x] **Phase 1: Java domain + REST API + tests.** Accounts, cards, authorization, history, idempotency, validation, error model, OpenAPI. *DoD: unit tests (JUnit 5 + Mockito) and integration tests (Testcontainers + real PostgreSQL) green in CI.*
- [ ] **Phase 2: Fraud scoring service + model evaluation.** Training on the Kaggle Credit Card Fraud dataset with class-imbalance handling, rules layer, FastAPI endpoint. *DoD: pytest green; precision / recall / PR-AUC reported on a held-out set.*
- [ ] **Phase 3: Integration + docker-compose.** Service-to-service call with timeout and fallback, structured logs, health checks, basic metrics. *DoD: `docker compose up` runs the whole system; failure of the scoring service is handled as documented.*
- [ ] **Phase 4: AWS + Terraform + CI/CD.** ECR, ECS Fargate (or Lambda), RDS, least-privilege IAM, AWS Budgets alarm, GitHub Actions deploy, dependency scanning. *DoD: one command deploys, one command (`terraform destroy`) removes everything; costs documented.*
- [ ] **Phase 5: Business analysis + polish.** Cost matrix, cost-minimizing threshold, results, limitations, next steps. *DoD: this README answers "why" for every design choice, for both engineers and PMs.*

## Results

*Not yet available.* This section will contain only measured results (model metrics on a held-out set, chosen threshold, expected-cost comparison, latency), with the method used to obtain them.

## Limitations

Documented honestly as the project progresses:
- No foreign-exchange conversion: the transaction currency must match the account currency.
- Authorization only: no capture/settlement, refunds or chargebacks yet.
- Idempotency keys are global and never expire (real systems scope them per client and expire them, e.g. after 24h).
- The fraud screener is a placeholder that approves everything until Phase 3.
- No authentication/authorization on the API yet.
- The public fraud dataset (Phase 2) uses anonymized PCA features, so the rules layer and the model see different kinds of signals.

## Requirements (to run it on my own machine)

| Tool | Version | Why | Install (Linux) |
|---|---|---|---|
| Git | any recent | version control | `sudo apt install git` (or `dnf install git`) |
| JDK | **21** (Temurin) | compile/run the Java service | [SDKMAN](https://sdkman.io): `curl -s "https://get.sdkman.io" \| bash`, then `sdk install java 21-tem` |
| Maven | none needed | the repo ships the Maven wrapper (`./mvnw`) | optional: `sdk install maven` |
| Docker Engine + Compose plugin | Docker 24+ | local PostgreSQL; Testcontainers in integration tests | [docs.docker.com/engine/install](https://docs.docker.com/engine/install/), then `sudo usermod -aG docker $USER` and log out/in |
| Python | **3.12+** | fraud scoring service | usually preinstalled; `uv` can also install it: `uv python install 3.12` |
| uv | latest | Python package/venv manager | `curl -LsSf https://astral.sh/uv/install.sh \| sh` |
| `psql` client | 16 (optional) | inspect the database by hand | `sudo apt install postgresql-client` |
| `jq`, `uuidgen` | any | used in the curl examples below | `sudo apt install jq uuid-runtime` |
| IDE | optional | IntelliJ IDEA Community (Java), VS Code (Python) | |
| Later (Phase 4) | | AWS CLI v2, Terraform 1.9+ | not needed yet |

Check: `java -version` (21), `docker run --rm hello-world`, `uv --version`, `python3 --version`.

## Running locally

```bash
# 1. Start PostgreSQL
docker compose up -d

# 2. Transaction Service (Flyway creates the tables on startup)
cd transaction-service
./mvnw spring-boot:run                 # http://localhost:8080/swagger-ui.html

# 3. Tests
./mvnw test                            # unit tests only (seconds, no Docker)
./mvnw verify                          # unit + integration tests (needs Docker)

# 4. Fraud Scoring Service (skeleton)
cd ../fraud-scoring-service
uv sync                                # creates .venv and installs locked dependencies
uv run pytest
uv run uvicorn app.main:app --reload --port 8000   # http://localhost:8000/docs
```

### Try a purchase with curl

```bash
API=http://localhost:8080/api/v1
ACCOUNT=$(curl -s -X POST $API/accounts -H 'Content-Type: application/json' \
  -d '{"holderName":"Ada Lovelace","creditLimit":{"amount":"500.00","currency":"MXN"}}' | jq -r .id)
CARD=$(curl -s -X POST $API/accounts/$ACCOUNT/cards | jq -r .id)
KEY=$(uuidgen)

# Run this twice: the second response has "Idempotent-Replayed: true" and the account is charged once.
curl -i -X POST $API/transactions -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d "{\"cardId\":\"$CARD\",\"amount\":{\"amount\":\"120.50\",\"currency\":\"MXN\"},
       \"merchant\":{\"name\":\"OXXO Reforma\",\"categoryCode\":\"5411\",\"country\":\"MX\"}}"

curl -s $API/accounts/$ACCOUNT | jq .availableCredit     # 379.50 MXN
```

## License

[MIT](LICENSE)
