package com.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One leg of an entry.
 *
 * <p>{@code amountMinor} is a signed {@code long} of minor currency units:
 * debits positive, credits negative. A {@code double} here would be a defect --
 * binary floating point cannot represent 0.01 exactly, so sums drift, and a
 * ledger whose totals drift is not a ledger.
 */
@Entity
@Table(name = "postings")
public class Posting {

    @Id
    private UUID id;

    @Column(name = "entry_id", nullable = false)
    private UUID entryId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Posting() {
        // JPA
    }

    public Posting(UUID id, UUID entryId, UUID accountId, long amountMinor,
                   String currency, Instant createdAt) {
        this.id = id;
        this.entryId = entryId;
        this.accountId = accountId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEntryId() {
        return entryId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isDebit() {
        return amountMinor > 0;
    }
}
