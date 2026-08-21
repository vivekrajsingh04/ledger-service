package com.ledger.service;

import com.ledger.repo.AccountBalanceRepository;
import com.ledger.repo.PostingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Continuously re-derives every balance from postings and compares it with the
 * materialised value.
 *
 * <p>The materialised {@code account_balances} table is a cache. Caches drift.
 * The difference between a ledger you can trust and one you hope is right is
 * whether anything checks -- so this runs on a schedule and exports the drift as
 * a gauge rather than only logging it, because a log line nobody greps is not
 * monitoring.
 *
 * <p>Two gauges are published:
 * <ul>
 *   <li>{@code ledger.reconciliation.drift.total} -- sum of |drift| in minor units.
 *       Alert on {@code > 0}. Not "> some threshold": any drift at all is a bug.</li>
 *   <li>{@code ledger.reconciliation.drift.accounts} -- how many accounts disagree.</li>
 * </ul>
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final AccountBalanceRepository balances;
    private final PostingRepository postings;
    private final AtomicLong totalDrift = new AtomicLong();
    private final AtomicLong driftingAccounts = new AtomicLong();
    private final AtomicLong globalPostingSum = new AtomicLong();

    public ReconciliationJob(AccountBalanceRepository balances,
                             PostingRepository postings,
                             MeterRegistry registry) {
        this.balances = balances;
        this.postings = postings;

        registry.gauge("ledger.reconciliation.drift.total", totalDrift, AtomicLong::get);
        registry.gauge("ledger.reconciliation.drift.accounts", driftingAccounts,
                AtomicLong::get);
        // Should be exactly 0 forever: every entry balances, so every posting ever
        // written must sum to zero. A non-zero value means the invariant broke.
        registry.gauge("ledger.postings.global.sum", globalPostingSum, AtomicLong::get);
    }

    @Scheduled(fixedDelayString = "${ledger.reconciliation.interval-ms:30000}",
            initialDelayString = "${ledger.reconciliation.initial-delay-ms:10000}")
    @Transactional(readOnly = true)
    public ReconciliationReport reconcile() {
        List<Object[]> drift = balances.findDrift();
        long total = balances.totalAbsoluteDrift();
        long globalSum = postings.sumOfAllPostings();

        totalDrift.set(total);
        driftingAccounts.set(drift.size());
        globalPostingSum.set(globalSum);

        if (!drift.isEmpty() || globalSum != 0L) {
            // Loud on purpose. This condition means the ledger is lying about at
            // least one balance.
            log.error("RECONCILIATION FAILED: {} accounts drifted, total |drift| = {} "
                            + "minor units, global posting sum = {} (must be 0)",
                    drift.size(), total, globalSum);
            for (Object[] row : drift) {
                log.error("  account={} name={} materialised={} recomputed={} drift={}",
                        row[0], row[1], row[2], row[3], row[4]);
            }
        } else {
            log.debug("reconciliation clean: 0 drift across all accounts");
        }

        return new ReconciliationReport(drift.size(), total, globalSum);
    }

    public record ReconciliationReport(int driftingAccounts, long totalAbsoluteDrift,
                                       long globalPostingSum) {

        public boolean isClean() {
            return driftingAccounts == 0 && totalAbsoluteDrift == 0 && globalPostingSum == 0;
        }
    }
}
