package com.ledger.service;

import java.util.Map;
import java.util.UUID;

/**
 * Applies signed balance deltas to the materialised balances table.
 *
 * <p>Two implementations exist and both are shipped, because the right answer
 * depends on contention and the only way to know is to measure:
 *
 * <ul>
 *   <li>{@link OptimisticBalanceUpdater} -- {@code @Version} column, retry on
 *       conflict. No locks held, so it is strictly cheaper when conflicts are
 *       rare, and degrades when they are not.</li>
 *   <li>{@link PessimisticBalanceUpdater} -- {@code SELECT ... FOR UPDATE} in a
 *       deterministic order. Pays for a lock on every write, and that cost does
 *       not grow with contention.</li>
 * </ul>
 *
 * <p>Select with {@code ledger.concurrency=optimistic|pessimistic}. The README
 * has the benchmark of both under low and high contention.
 */
public interface BalanceUpdater {

    /**
     * @param deltas accountId -> signed minor units to add. Implementations must
     *               tolerate being called with the map in any iteration order.
     */
    void applyDeltas(Map<UUID, Long> deltas);

    String strategyName();
}
