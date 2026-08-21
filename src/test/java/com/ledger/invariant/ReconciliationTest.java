package com.ledger.invariant;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledger.AbstractPostgresTest;
import com.ledger.TestLedgerFixtures;
import com.ledger.repo.AccountBalanceRepository;
import com.ledger.repo.PostingRepository;
import com.ledger.service.ReconciliationJob;
import com.ledger.service.RetryingLedgerFacade;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The materialised balances table must equal the sum recomputed from postings.
 *
 * <p>Also proves the job <em>detects</em> drift rather than merely reporting
 * clean: a reconciliation that has never failed on a known-bad state is a
 * reconciliation nobody has tested.
 */
@DisplayName("reconciliation: materialised balances vs. recomputed sums")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReconciliationTest extends AbstractPostgresTest {

    @Autowired
    RetryingLedgerFacade ledger;
    @Autowired
    TestLedgerFixtures fixtures;
    @Autowired
    ReconciliationJob reconciliation;
    @Autowired
    AccountBalanceRepository balances;
    @Autowired
    PostingRepository postings;
    @Autowired
    MeterRegistry meters;
    @Autowired
    DataSource dataSource;

    @Test
    @Order(1)
    @DisplayName("a healthy ledger reconciles to exactly zero drift")
    void healthyLedgerHasNoDrift() {
        UUID from = fixtures.createLiability("rec-from");
        UUID to = fixtures.createAsset("rec-to");

        for (int i = 0; i < 25; i++) {
            ledger.createEntry("rec-" + i + "-" + UUID.randomUUID(),
                    TestLedgerFixtures.transfer(from, to, 137L));
        }

        ReconciliationJob.ReconciliationReport report = reconciliation.reconcile();

        assertThat(report.isClean()).as("report: %s", report).isTrue();
        assertThat(report.driftingAccounts()).isZero();
        assertThat(report.totalAbsoluteDrift()).isZero();
        assertThat(report.globalPostingSum()).isZero();

        assertThat(balances.findByAccountId(to).orElseThrow().getBalanceMinor())
                .isEqualTo(postings.currentBalance(to));
    }

    @Test
    @Order(2)
    @DisplayName("drift is detected and exported as a metric, not just logged")
    void injectedDriftIsDetectedAndExported() {
        UUID from = fixtures.createLiability("drift-from");
        UUID to = fixtures.createAsset("drift-to");
        ledger.createEntry("drift-" + UUID.randomUUID(),
                TestLedgerFixtures.transfer(from, to, 5_000L));

        assertThat(reconciliation.reconcile().isClean()).isTrue();

        // Corrupt the cache directly, simulating the bug class this job exists to
        // catch: the materialised balance and the postings disagreeing.
        new JdbcTemplate(dataSource).update(
                "UPDATE account_balances SET balance_minor = balance_minor + 999 "
                        + "WHERE account_id = ?", to);

        ReconciliationJob.ReconciliationReport report = reconciliation.reconcile();

        assertThat(report.isClean()).as("drift must not go unnoticed").isFalse();
        assertThat(report.driftingAccounts()).isEqualTo(1);
        assertThat(report.totalAbsoluteDrift()).isEqualTo(999L);

        // The gauge is what an alert fires on. If it is not published, the job is
        // decorative.
        assertThat(meters.get("ledger.reconciliation.drift.total").gauge().value())
                .isEqualTo(999.0);
        assertThat(meters.get("ledger.reconciliation.drift.accounts").gauge().value())
                .isEqualTo(1.0);

        // The postings themselves were untouched, so the ledger's own truth is
        // still intact -- which is exactly why the cache is recoverable.
        assertThat(postings.sumOfAllPostings()).isZero();

        // Repair from the source of truth and confirm the ledger reconciles
        // again. Two reasons this matters:
        //
        //  1. It proves drift is *recoverable* -- the materialised balance can
        //     always be rebuilt by summing postings, which is the whole argument
        //     for treating it as a cache rather than the truth.
        //  2. reconcile() is global, and this class shares one database with
        //     every other test. Leaving 999 units of injected drift behind made
        //     healthyLedgerHasNoDrift fail depending on execution order. A test
        //     that deliberately corrupts shared state owns cleaning it up.
        new JdbcTemplate(dataSource).update(
                "UPDATE account_balances b SET balance_minor = ("
                        + "  SELECT COALESCE(SUM(p.amount_minor), 0) FROM postings p"
                        + "  WHERE p.account_id = b.account_id) "
                        + "WHERE b.account_id = ?", to);

        ReconciliationJob.ReconciliationReport repaired = reconciliation.reconcile();
        assertThat(repaired.isClean())
                .as("rebuilding the balance from postings must clear the drift: %s", repaired)
                .isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("reversal restores the balance to its pre-entry value")
    void reversalRestoresTheBalance() {
        UUID from = fixtures.createLiability("rev-from");
        UUID to = fixtures.createAsset("rev-to");

        var created = ledger.createEntry("rev-orig-" + UUID.randomUUID(),
                TestLedgerFixtures.transfer(from, to, 8_800L));
        assertThat(postings.currentBalance(to)).isEqualTo(8_800L);

        ledger.reverseEntry("rev-rev-" + UUID.randomUUID(), created.value().id());

        assertThat(postings.currentBalance(to))
                .as("the reversal must net the account back to zero")
                .isZero();
        assertThat(balances.findByAccountId(to).orElseThrow().getBalanceMinor()).isZero();

        // Crucially, by ADDING history rather than removing it.
        assertThat(postings.findByEntryIdOrderByAmountMinorDesc(created.value().id()))
                .as("the original postings are still there, untouched")
                .hasSize(2);

        assertThat(reconciliation.reconcile().isClean()).isTrue();
    }
}
