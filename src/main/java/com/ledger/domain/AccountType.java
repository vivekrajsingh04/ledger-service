package com.ledger.domain;

/**
 * The five accounting account types.
 *
 * <p>Normal balance matters for presentation, not for storage: every posting is
 * stored as a signed {@code amount_minor} (debits positive, credits negative)
 * regardless of type. {@link #normalBalanceIsDebit()} tells the API layer whether
 * a positive stored balance should be shown as positive to a human.
 */
public enum AccountType {
    ASSET(true),
    LIABILITY(false),
    EQUITY(false),
    REVENUE(false),
    EXPENSE(true);

    private final boolean debitNormal;

    AccountType(boolean debitNormal) {
        this.debitNormal = debitNormal;
    }

    public boolean normalBalanceIsDebit() {
        return debitNormal;
    }
}
