package com.ledger.service;

import com.ledger.domain.AccountBalance;
import com.ledger.repo.AccountBalanceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Optimistic concurrency via the JPA {@code @Version} column.
 *
 * <p>Hibernate turns each balance write into
 * {@code UPDATE account_balances SET balance_minor = ?, version = version + 1
 * WHERE account_id = ? AND version = ?}. If another transaction moved the row
 * first, that matches zero rows and Hibernate raises
 * {@link org.springframework.orm.ObjectOptimisticLockingFailureException} at
 * flush time.
 *
 * <p>Ordering still matters here. See {@link #applyDeltas} -- an UPDATE takes a
 * row lock even when no lock was asked for, so deltas are applied in ascending
 * account order exactly as the pessimistic strategy does.
 *
 * <p>Note where the retry <em>is not</em>: it is not here. Retrying inside a
 * transaction that has already failed is useless -- the transaction is marked
 * rollback-only and every subsequent statement fails too. The retry lives in
 * {@link RetryingLedgerFacade}, which re-runs the whole transaction from the
 * start. Getting this wrong is the single most common optimistic-locking bug.
 */
@Component
@ConditionalOnProperty(name = "ledger.concurrency", havingValue = "optimistic",
        matchIfMissing = true)
public class OptimisticBalanceUpdater implements BalanceUpdater {

    private final AccountBalanceRepository balances;

    public OptimisticBalanceUpdater(AccountBalanceRepository balances) {
        this.balances = balances;
    }

    @Override
    public void applyDeltas(Map<UUID, Long> deltas) {
        // Ordered ascending, and flushed in that order, for exactly the same
        // reason the pessimistic strategy sorts before locking.
        //
        // "Optimistic means no locks" is the intuition, and it is wrong. There is
        // no *explicit* lock, but every UPDATE still takes a row lock that is held
        // until the transaction ends. Two transactions touching the same pair of
        // accounts in opposite orders therefore deadlock just as readily here as
        // under SELECT FOR UPDATE -- Postgres aborts one with SQLSTATE 40P01.
        //
        // This was not theoretical: applying deltas in map-iteration order
        // produced 777 deadlocks in the 100-thread hot-account test while the
        // pessimistic strategy, which already sorted, passed the same test
        // untouched.
        //
        // saveAndFlush rather than save: Hibernate otherwise defers the UPDATEs to
        // transaction flush and is free to emit them in an order of its own, so
        // sorting the iteration alone would not fix the SQL issue order.
        List<UUID> ordered = new ArrayList<>(deltas.keySet());
        ordered.sort(UUID::compareTo);

        for (UUID accountId : ordered) {
            AccountBalance balance = balances.findByAccountId(accountId)
                    .orElseThrow(() -> new IllegalStateException(
                            "no balance row for account " + accountId
                                    + "; accounts must be created with a balance row"));
            balance.applyDelta(deltas.get(accountId));
            balances.saveAndFlush(balance);
        }
    }

    @Override
    public String strategyName() {
        return "optimistic";
    }
}
