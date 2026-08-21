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
 * Pessimistic concurrency via {@code SELECT ... FOR UPDATE} with a deterministic
 * lock order.
 *
 * <p><b>Why the sort matters.</b> Consider two concurrent transfers between the
 * same pair of accounts, A -> B and B -> A. If each transaction locks accounts in
 * the order its own request happens to list them:
 *
 * <pre>
 *   txn1: LOCK A ......... waits for B
 *   txn2: LOCK B ......... waits for A
 * </pre>
 *
 * <p>That is a cycle in the wait-for graph -- a textbook deadlock. Postgres
 * detects it after {@code deadlock_timeout} (1s by default) and aborts one
 * transaction with SQLSTATE 40P01. Nothing is corrupted, but a correctness bug
 * has become a one-second latency cliff that only appears under load, which is
 * the worst way to discover it.
 *
 * <p>Sorting the account ids ascending before locking gives every transaction the
 * same total order, so no cycle can form. Any total order works as long as all
 * writers agree on it; ascending UUID is simply the one that needs no explanation.
 */
@Component
@ConditionalOnProperty(name = "ledger.concurrency", havingValue = "pessimistic")
public class PessimisticBalanceUpdater implements BalanceUpdater {

    private final AccountBalanceRepository balances;

    public PessimisticBalanceUpdater(AccountBalanceRepository balances) {
        this.balances = balances;
    }

    @Override
    public void applyDeltas(Map<UUID, Long> deltas) {
        // Sort here, not at the call site: a lock ordering that depends on a
        // caller remembering to sort is a lock ordering that will eventually be
        // violated.
        List<UUID> ordered = new ArrayList<>(deltas.keySet());
        ordered.sort(UUID::compareTo);

        List<AccountBalance> locked = balances.lockAllOrdered(ordered);
        if (locked.size() != ordered.size()) {
            throw new IllegalStateException(
                    "expected " + ordered.size() + " balance rows to lock but found "
                            + locked.size() + "; accounts must be created with a balance row");
        }

        for (AccountBalance balance : locked) {
            balance.applyDelta(deltas.get(balance.getAccountId()));
        }
        balances.saveAll(locked);
    }

    @Override
    public String strategyName() {
        return "pessimistic";
    }
}
