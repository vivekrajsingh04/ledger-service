-- System accounts every deployment needs. Seeded as a migration rather than by
-- application startup code so the set is versioned and identical everywhere.
--
-- Deterministic UUIDs so tests and fixtures can reference them by literal.
INSERT INTO accounts (id, name, type, currency) VALUES
    ('00000000-0000-0000-0000-000000000001', 'system:cash',            'ASSET',     'INR'),
    ('00000000-0000-0000-0000-000000000002', 'system:customer_payable','LIABILITY', 'INR'),
    ('00000000-0000-0000-0000-000000000003', 'system:revenue_fees',    'REVENUE',   'INR'),
    ('00000000-0000-0000-0000-000000000004', 'system:expense_losses',  'EXPENSE',   'INR'),
    ('00000000-0000-0000-0000-000000000005', 'system:opening_equity',  'EQUITY',    'INR')
ON CONFLICT (id) DO NOTHING;

INSERT INTO account_balances (account_id, balance_minor, version)
SELECT id, 0, 0 FROM accounts
ON CONFLICT (account_id) DO NOTHING;
