-- =====================================================================================
-- V1: core schema for accounts, cards and transactions.
--
-- Flyway runs every file named V<version>__<description>.sql exactly once, in order, and
-- records it in the flyway_schema_history table. Rule: NEVER edit a migration that has
-- already run anywhere; add V2, V3... instead. The schema history is code, versioned with Git.
--
-- Constraints repeat the domain invariants (Account.java, Transaction.java). Why both?
-- The Java checks give good error messages; the database checks protect the data even
-- from bugs, manual SQL or a future second service writing to these tables.
-- =====================================================================================

CREATE TABLE accounts
(
    id                     UUID PRIMARY KEY,
    holder_name            VARCHAR(200) NOT NULL,
    currency               CHAR(3)      NOT NULL,
    -- Money as BIGINT minor units (cents). Never FLOAT/REAL/DOUBLE. See docs/adr/0002.
    credit_limit_minor     BIGINT       NOT NULL,
    available_credit_minor BIGINT       NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_accounts_credit_limit_non_negative CHECK (credit_limit_minor >= 0),
    CONSTRAINT chk_accounts_available_credit_range
        CHECK (available_credit_minor BETWEEN 0 AND credit_limit_minor)
);

CREATE TABLE cards
(
    id           UUID PRIMARY KEY,
    account_id   UUID        NOT NULL REFERENCES accounts (id),
    last4        CHAR(4)     NOT NULL,
    expiry_year  SMALLINT    NOT NULL,
    expiry_month SMALLINT    NOT NULL,
    status       VARCHAR(16) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_cards_last4_digits CHECK (last4 ~ '^[0-9]{4}$'),
    CONSTRAINT chk_cards_expiry_month CHECK (expiry_month BETWEEN 1 AND 12),
    CONSTRAINT chk_cards_status CHECK (status IN ('ACTIVE', 'BLOCKED'))
);

-- PostgreSQL does NOT index foreign keys automatically. Without this, "cards of account X"
-- is a full table scan, and deleting an account would scan cards to check the FK.
CREATE INDEX idx_cards_account_id ON cards (account_id);

CREATE TABLE transactions
(
    id                     UUID PRIMARY KEY,
    card_id                UUID         NOT NULL REFERENCES cards (id),
    account_id             UUID         NOT NULL REFERENCES accounts (id),
    amount_minor           BIGINT       NOT NULL,
    currency               CHAR(3)      NOT NULL,
    merchant_name          VARCHAR(200) NOT NULL,
    merchant_category_code CHAR(4)      NOT NULL,
    merchant_country       CHAR(2)      NOT NULL,
    status                 VARCHAR(16)  NOT NULL,
    decline_reason         VARCHAR(32),
    idempotency_key        VARCHAR(255) NOT NULL,
    request_fingerprint    CHAR(64)     NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_transactions_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT chk_transactions_status CHECK (status IN ('APPROVED', 'DECLINED', 'PENDING_REVIEW')),
    -- A decline has a reason; nothing else does.
    CONSTRAINT chk_transactions_decline_reason CHECK ((status = 'DECLINED') = (decline_reason IS NOT NULL)),
    -- The idempotency guarantee ultimately rests on this constraint (see AuthorizationService).
    -- A UNIQUE constraint is backed by a unique B-tree index, so key lookups are O(log n).
    CONSTRAINT uq_transactions_idempotency_key UNIQUE (idempotency_key)
);

-- Serves the history query: WHERE card_id = ? ORDER BY created_at DESC, id DESC.
-- Column order matters: equality column first, then the sort columns. The index is already
-- in the requested order, so PostgreSQL reads it sequentially with no sort step.
CREATE INDEX idx_transactions_card_history ON transactions (card_id, created_at DESC, id DESC);
