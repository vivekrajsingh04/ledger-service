package com.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Materialised running balance. An optimisation, never the source of truth --
 * the truth is {@code SUM(postings.amount_minor)}, and the reconciliation job
 * asserts the two agree.
 *
 * <p>{@code @Version} drives the optimistic strategy: Hibernate appends
 * {@code WHERE version = ?} to the UPDATE and throws
 * {@link org.springframework.orm.ObjectOptimisticLockingFailureException} when it
 * matches zero rows, meaning another transaction moved this balance first.
 */
@Entity
@Table(name = "account_balances")
public class AccountBalance {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AccountBalance() {
        // JPA
    }

    public AccountBalance(UUID accountId, long balanceMinor) {
        this.accountId = accountId;
        this.balanceMinor = balanceMinor;
        this.updatedAt = Instant.now();
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getBalanceMinor() {
        return balanceMinor;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Applies a signed delta. Overflow is checked rather than allowed to wrap:
     * a silently negated balance is worse than a failed request.
     */
    public void applyDelta(long deltaMinor) {
        this.balanceMinor = Math.addExact(this.balanceMinor, deltaMinor);
        this.updatedAt = Instant.now();
    }
}
