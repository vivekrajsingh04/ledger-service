-- ============================================================================
-- Double-entry ledger baseline schema.
--
-- Money is BIGINT minor units throughout (paise, cents). Never NUMERIC with a
-- scale we might get wrong, and never a floating-point type: 0.1 + 0.2 is not
-- 0.3 in binary floating point, and a ledger that cannot represent its own
-- totals exactly is not a ledger.
--
-- Currency is VARCHAR(3) with a format CHECK rather than a fixed-width CHAR.
-- Postgres CHAR(n) is bpchar: blank-padded, so 'INR' and 'INR ' compare in ways
-- that surprise, and Postgres' own docs advise against it. The CHECK gives the
-- same guarantee without the padding semantics, and it matches how Hibernate
-- maps a plain String -- which is what lets ddl-auto=validate stay on and catch
-- a schema/entity drift like this one at application startup.
-- ============================================================================

CREATE TABLE accounts (
    id          UUID PRIMARY KEY,
    name        TEXT        NOT NULL,
    type        TEXT        NOT NULL
                CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    currency    VARCHAR(3)  NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_accounts_name ON accounts (name);

-- ----------------------------------------------------------------------------
-- Journal entries are append-only. There is no UPDATE path and no DELETE path
-- in the application; corrections are new entries linked via reverses_entry_id.
-- ----------------------------------------------------------------------------
CREATE TABLE journal_entries (
    id                 UUID PRIMARY KEY,
    idempotency_key    TEXT        NOT NULL UNIQUE,
    request_hash       TEXT        NOT NULL,
    -- Snapshot of the response we returned the first time this key was seen.
    -- Replaying the key returns this verbatim, so a retry is indistinguishable
    -- from the original call. Stripe's idempotency contract, section "Replayed
    -- responses": https://docs.stripe.com/api/idempotent_requests
    response_snapshot  JSONB,
    reverses_entry_id  UUID        NULL REFERENCES journal_entries (id),
    description        TEXT        NOT NULL DEFAULT '',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- An entry may be reversed at most once. Without this, two concurrent reversal
-- requests both succeed and the account is credited twice.
CREATE UNIQUE INDEX idx_entries_reverses ON journal_entries (reverses_entry_id)
    WHERE reverses_entry_id IS NOT NULL;

CREATE INDEX idx_entries_created_at ON journal_entries (created_at, id);

-- ----------------------------------------------------------------------------
-- Postings. `amount_minor` is SIGNED: debits positive, credits negative. Storing
-- a sign rather than a separate direction column is what makes the balance
-- invariant expressible as a single SUM = 0.
-- ----------------------------------------------------------------------------
CREATE TABLE postings (
    id          UUID PRIMARY KEY,
    entry_id    UUID        NOT NULL REFERENCES journal_entries (id),
    account_id  UUID        NOT NULL REFERENCES accounts (id),
    amount_minor BIGINT     NOT NULL CHECK (amount_minor <> 0),
    currency    VARCHAR(3)  NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_postings_entry   ON postings (entry_id);
-- Covers both the as-of balance query and the statement's keyset pagination.
CREATE INDEX idx_postings_account ON postings (account_id, created_at DESC, id DESC);

-- ----------------------------------------------------------------------------
-- Materialised balances. This table is an optimisation, not the truth: the truth
-- is SUM(postings). The reconciliation job asserts they agree and exports the
-- drift as a metric.
--
-- `version` is the JPA @Version column used by the optimistic strategy.
-- ----------------------------------------------------------------------------
CREATE TABLE account_balances (
    account_id    UUID PRIMARY KEY REFERENCES accounts (id),
    balance_minor BIGINT      NOT NULL DEFAULT 0,
    version       BIGINT      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================================
-- Defence in depth: the accounting invariant enforced in the database.
--
-- SUM(amount_minor) = 0 for every entry, per currency. Checking this in Java is
-- necessary but not sufficient -- a bug in the service, a migration script, or
-- someone with psql access can all violate it. A DEFERRABLE INITIALLY DEFERRED
-- constraint trigger fires at COMMIT, by which time all of an entry's postings
-- are present, so a balanced entry inserted row-by-row is never spuriously
-- rejected while an unbalanced one can never be committed.
-- ============================================================================
CREATE OR REPLACE FUNCTION check_entry_balances() RETURNS TRIGGER AS $$
DECLARE
    offending RECORD;
BEGIN
    SELECT p.currency, SUM(p.amount_minor) AS total, count(*) AS n
      INTO offending
      FROM postings p
     WHERE p.entry_id = NEW.entry_id
     GROUP BY p.currency
    HAVING SUM(p.amount_minor) <> 0
     LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION
            'entry % does not balance in %: sum(amount_minor) = % across % postings',
            NEW.entry_id, offending.currency, offending.total, offending.n
            USING ERRCODE = 'check_violation';
    END IF;

    -- A double-entry entry needs at least two postings. One posting that happens
    -- to sum to zero is impossible (amount_minor <> 0 is a CHECK), but a single
    -- currency group with one row would otherwise slip through the sum test.
    IF (SELECT count(*) FROM postings WHERE entry_id = NEW.entry_id) < 2 THEN
        RAISE EXCEPTION 'entry % has fewer than two postings', NEW.entry_id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER entry_must_balance
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_entry_balances();

-- ============================================================================
-- Immutability, also enforced in the database. The application never issues
-- these statements; this makes it impossible for anyone else to either.
-- ============================================================================
CREATE OR REPLACE FUNCTION reject_posting_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'postings are append-only: % on posting % is not permitted. '
        'Correct a mistake with a reversing entry, not an update.',
        TG_OP, OLD.id
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER postings_are_immutable
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION reject_posting_mutation();

CREATE OR REPLACE FUNCTION reject_entry_mutation() RETURNS TRIGGER AS $$
BEGIN
    -- The response snapshot is written once, immediately after the entry is
    -- created and inside the same transaction, so that one column is allowed to
    -- change from NULL. Everything else about a committed entry is frozen.
    IF TG_OP = 'UPDATE'
       AND OLD.response_snapshot IS NULL
       AND NEW.id = OLD.id
       AND NEW.idempotency_key = OLD.idempotency_key
       AND NEW.request_hash = OLD.request_hash
       AND NEW.description = OLD.description
       AND NEW.created_at = OLD.created_at
       AND NEW.reverses_entry_id IS NOT DISTINCT FROM OLD.reverses_entry_id THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION
        'journal entries are append-only: % on entry % is not permitted',
        TG_OP, OLD.id
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER entries_are_immutable
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION reject_entry_mutation();

-- ============================================================================
-- Reconciliation view: materialised balance vs. the truth recomputed from
-- postings. Any non-zero `drift_minor` is a bug, and the scheduled job exports
-- it to Prometheus rather than only logging it.
-- ============================================================================
CREATE OR REPLACE VIEW balance_reconciliation AS
SELECT
    a.id                                        AS account_id,
    a.name                                      AS account_name,
    COALESCE(b.balance_minor, 0)                AS materialised_minor,
    COALESCE(SUM(p.amount_minor), 0)            AS recomputed_minor,
    COALESCE(b.balance_minor, 0) - COALESCE(SUM(p.amount_minor), 0) AS drift_minor
FROM accounts a
LEFT JOIN account_balances b ON b.account_id = a.id
LEFT JOIN postings p         ON p.account_id = a.id
GROUP BY a.id, a.name, b.balance_minor;
