package com.ledger.invariant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledger.AbstractPostgresTest;
import com.ledger.TestLedgerFixtures;
import com.ledger.service.RetryingLedgerFacade;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Try to break the ledger by going around the application entirely.
 *
 * <p>Every test here writes raw SQL straight at the database, bypassing the
 * service, its validation, and JPA. If the invariants live only in Java, all of
 * these succeed. That is the point: an accounting guarantee that any script with
 * a psql connection can violate is not a guarantee.
 */
@DisplayName("invariants cannot be bypassed, even with raw SQL")
class InvariantBypassTest extends AbstractPostgresTest {

    @Autowired
    DataSource dataSource;
    @Autowired
    TestLedgerFixtures fixtures;
    @Autowired
    RetryingLedgerFacade ledger;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    /**
     * Runs statements in ONE transaction and commits.
     *
     * <p>This matters more than it looks. The balance trigger is
     * {@code DEFERRABLE INITIALLY DEFERRED}, so it fires at COMMIT. Issuing each
     * INSERT on an autocommit connection would commit after the first leg and
     * trip the "fewer than two postings" check on a perfectly valid entry -- the
     * test would fail for the wrong reason and look like a schema bug.
     */
    private void inOneTransaction(String... statements) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                for (String sql : statements) {
                    stmt.execute(sql);
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private static String insertEntry(UUID entryId, String description) {
        return """
                INSERT INTO journal_entries
                    (id, idempotency_key, request_hash, description, created_at)
                VALUES ('%s', 'raw-%s', 'hash', '%s', now())
                """.formatted(entryId, entryId, description);
    }

    private static String insertPosting(UUID entryId, UUID accountId, long amount,
                                        String currency) {
        return """
                INSERT INTO postings (id, entry_id, account_id, amount_minor, currency, created_at)
                VALUES ('%s', '%s', '%s', %d, '%s', now())
                """.formatted(UUID.randomUUID(), entryId, accountId, amount, currency);
    }

    @Test
    @DisplayName("an unbalanced entry inserted with raw SQL is rejected at COMMIT")
    void unbalancedEntryIsRejectedByTheTrigger() {
        UUID a = fixtures.createAsset("bypass-a");
        UUID b = fixtures.createLiability("bypass-b");
        UUID entryId = UUID.randomUUID();

        // 100 debited, only 60 credited. Java never sees this request.
        assertThatThrownBy(() -> inOneTransaction(
                insertEntry(entryId, "smuggled"),
                insertPosting(entryId, a, 100L, "INR"),
                insertPosting(entryId, b, -60L, "INR")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("does not balance");

        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM postings WHERE entry_id = ?", Long.class, entryId))
                .as("nothing from the rejected transaction survives")
                .isZero();
    }

    @Test
    @DisplayName("an entry balanced per currency is accepted, including multi-currency")
    void balancedPerCurrencyIsAccepted() throws SQLException {
        UUID inrA = fixtures.createAsset("mc-inr-a");
        UUID inrB = fixtures.createLiability("mc-inr-b");
        UUID usdA = fixtures.createAccount("mc-usd-a",
                com.ledger.domain.AccountType.ASSET, "USD");
        UUID usdB = fixtures.createAccount("mc-usd-b",
                com.ledger.domain.AccountType.LIABILITY, "USD");
        UUID entryId = UUID.randomUUID();

        // Balances in INR and separately in USD. The trigger groups by currency,
        // so a cross-currency "sum" that happened to reach zero would NOT save an
        // entry whose INR legs do not balance.
        inOneTransaction(
                insertEntry(entryId, "multi-currency"),
                insertPosting(entryId, inrA, 500L, "INR"),
                insertPosting(entryId, inrB, -500L, "INR"),
                insertPosting(entryId, usdA, 700L, "USD"),
                insertPosting(entryId, usdB, -700L, "USD"));

        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM postings WHERE entry_id = ?", Long.class, entryId))
                .isEqualTo(4L);
    }

    @Test
    @DisplayName("currencies that only balance when summed together are still rejected")
    void crossCurrencyNettingIsRejected() {
        UUID inr = fixtures.createAsset("net-inr");
        UUID usd = fixtures.createAccount("net-usd",
                com.ledger.domain.AccountType.LIABILITY, "USD");
        UUID entryId = UUID.randomUUID();

        // +500 INR and -500 USD sums to zero only if you pretend a rupee is a
        // dollar. Grouping by currency is what stops that.
        assertThatThrownBy(() -> inOneTransaction(
                insertEntry(entryId, "netting"),
                insertPosting(entryId, inr, 500L, "INR"),
                insertPosting(entryId, usd, -500L, "USD")))
                .hasMessageContaining("does not balance");
    }

    @Test
    @DisplayName("a single-posting entry is rejected")
    void singlePostingEntryIsRejected() {
        UUID a = fixtures.createAsset("single-a");
        UUID entryId = UUID.randomUUID();

        // Rejected by the *balance* branch, not the posting-count branch. A lone
        // posting can never sum to zero, because `amount_minor <> 0` is a CHECK
        // constraint -- so the balance test always catches this first and the
        // count test is unreachable by this route. The count check still earns
        // its place as defence in depth if that CHECK were ever relaxed, but the
        // assertion here has to name the message the database actually produces.
        assertThatThrownBy(() -> inOneTransaction(
                insertEntry(entryId, "single"),
                insertPosting(entryId, a, 100L, "INR")))
                .hasMessageContaining("does not balance")
                .hasMessageContaining("across 1 postings");
    }

    @Test
    @DisplayName("a zero-amount posting is rejected by a CHECK constraint")
    void zeroAmountPostingIsRejected() {
        UUID a = fixtures.createAsset("zero-a");
        UUID b = fixtures.createLiability("zero-b");
        UUID entryId = UUID.randomUUID();

        assertThatThrownBy(() -> inOneTransaction(
                insertEntry(entryId, "zero"),
                insertPosting(entryId, a, 0L, "INR"),
                insertPosting(entryId, b, 0L, "INR")))
                .hasMessageContaining("amount_minor");
    }

    @Test
    @DisplayName("UPDATE on a posting is rejected: the ledger is append-only")
    void postingsCannotBeUpdated() {
        UUID from = fixtures.createLiability("imm-from");
        UUID to = fixtures.createAsset("imm-to");
        ledger.createEntry("imm-" + UUID.randomUUID(),
                TestLedgerFixtures.transfer(from, to, 4_200L));

        JdbcTemplate jdbc = jdbc();
        UUID postingId = jdbc.queryForObject(
                "SELECT id FROM postings WHERE account_id = ? LIMIT 1", UUID.class, to);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE postings SET amount_minor = 999999 WHERE id = ?", postingId))
                .hasMessageContaining("append-only");

        assertThat(jdbc.queryForObject(
                "SELECT amount_minor FROM postings WHERE id = ?", Long.class, postingId))
                .isEqualTo(4_200L);
    }

    @Test
    @DisplayName("DELETE on a posting is rejected: corrections are reversals, not deletions")
    void postingsCannotBeDeleted() {
        UUID from = fixtures.createLiability("del-from");
        UUID to = fixtures.createAsset("del-to");
        ledger.createEntry("del-" + UUID.randomUUID(),
                TestLedgerFixtures.transfer(from, to, 1_000L));

        JdbcTemplate jdbc = jdbc();
        UUID postingId = jdbc.queryForObject(
                "SELECT id FROM postings WHERE account_id = ? LIMIT 1", UUID.class, to);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM postings WHERE id = ?", postingId))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("a committed journal entry cannot be rewritten")
    void journalEntriesCannotBeRewritten() {
        UUID from = fixtures.createLiability("je-from");
        UUID to = fixtures.createAsset("je-to");
        var result = ledger.createEntry("je-" + UUID.randomUUID(),
                TestLedgerFixtures.transfer(from, to, 250L));

        JdbcTemplate jdbc = jdbc();
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE journal_entries SET description = 'tampered' WHERE id = ?",
                result.value().id()))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("an entry cannot be reversed twice, even by racing raw inserts")
    void anEntryCannotBeReversedTwice() {
        UUID from = fixtures.createLiability("dbl-from");
        UUID to = fixtures.createAsset("dbl-to");
        var original = ledger.createEntry("dbl-" + UUID.randomUUID(),
                TestLedgerFixtures.transfer(from, to, 900L));

        ledger.reverseEntry("dbl-rev-" + UUID.randomUUID(), original.value().id());

        // The service checks first, but the partial unique index is what makes it
        // true under a concurrent second caller that passed the same check.
        UUID smuggled = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc().update("""
                INSERT INTO journal_entries
                    (id, idempotency_key, request_hash, description,
                     reverses_entry_id, created_at)
                VALUES (?, ?, 'hash', 'second reversal', ?, now())
                """, smuggled, "raw-dbl-" + smuggled, original.value().id()))
                .hasMessageContaining("idx_entries_reverses");
    }

    @Test
    @DisplayName("the as-of balance is reproducible from history alone")
    void asOfBalanceReconstructsThePast() {
        UUID from = fixtures.createLiability("asof-from");
        UUID to = fixtures.createAsset("asof-to");

        ledger.createEntry("asof-1-" + UUID.randomUUID(),
                TestLedgerFixtures.transfer(from, to, 1_000L));
        Instant afterFirst = Instant.now();

        // A gap wide enough that the second entry is unambiguously later.
        sleepMillis(50);
        ledger.createEntry("asof-2-" + UUID.randomUUID(),
                TestLedgerFixtures.transfer(from, to, 2_500L));

        JdbcTemplate jdbc = jdbc();
        Long asOfFirst = jdbc.queryForObject("""
                SELECT COALESCE(SUM(p.amount_minor), 0)
                  FROM postings p JOIN journal_entries e ON e.id = p.entry_id
                 WHERE p.account_id = ? AND e.created_at <= ?
                """, Long.class, to, Timestamp.from(afterFirst));
        Long now = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM postings WHERE account_id = ?",
                Long.class, to);

        assertThat(asOfFirst).as("balance as of just after the first entry").isEqualTo(1_000L);
        assertThat(now).isEqualTo(3_500L);
    }

    @Test
    @DisplayName("the reconciliation view agrees with a hand-written recomputation")
    void reconciliationViewMatchesManualRecomputation() {
        UUID from = fixtures.createLiability("view-from");
        UUID to = fixtures.createAsset("view-to");
        for (int i = 0; i < 5; i++) {
            ledger.createEntry("view-" + i + "-" + UUID.randomUUID(),
                    TestLedgerFixtures.transfer(from, to, 111L));
        }

        List<Long> drift = jdbc().queryForList(
                "SELECT drift_minor FROM balance_reconciliation WHERE account_id IN (?, ?)",
                Long.class, from, to);

        assertThat(drift).isNotEmpty().allMatch(d -> d == 0L);
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
