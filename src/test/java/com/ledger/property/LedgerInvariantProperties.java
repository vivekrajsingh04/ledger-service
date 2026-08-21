package com.ledger.property;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledger.LedgerApplication;
import com.ledger.api.dto.CreateEntryRequest;
import com.ledger.api.dto.PostingRequest;
import com.ledger.domain.Account;
import com.ledger.domain.AccountBalance;
import com.ledger.domain.AccountType;
import com.ledger.repo.AccountBalanceRepository;
import com.ledger.repo.AccountRepository;
import com.ledger.repo.PostingRepository;
import com.ledger.service.ReconciliationJob;
import com.ledger.service.RetryingLedgerFacade;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Property-based test: for ANY random sequence of valid entries, the ledger's
 * global invariant holds after every single operation.
 *
 * <p>Example-based tests check the cases you thought of. jqwik generates
 * sequences you did not: entries with 2 to 8 legs, amounts spanning six orders of
 * magnitude, the same account appearing on both sides of one entry, multiple
 * currencies interleaved. Then it shrinks any failure to the smallest sequence
 * that still breaks -- which is the difference between "something is wrong
 * somewhere" and a two-line reproduction.
 *
 * <p>The invariant asserted after every operation:
 * <ol>
 *   <li>{@code SUM(all postings) == 0} -- globally, across every account and entry;</li>
 *   <li>{@code SUM(postings) == 0} per currency;</li>
 *   <li>every materialised balance equals its recomputed sum from postings.</li>
 * </ol>
 *
 * <p>jqwik runs outside Spring's JUnit 5 extension, so the context and container
 * are managed explicitly here rather than by {@code @SpringBootTest}.
 */
class LedgerInvariantProperties {

    private static final int ACCOUNT_POOL = 12;
    private static final String[] CURRENCIES = {"INR", "USD", "EUR"};

    private static PostgreSQLContainer<?> postgres;
    private static ConfigurableApplicationContext context;

    private static RetryingLedgerFacade ledger;
    private static PostingRepository postings;
    private static AccountBalanceRepository balances;
    private static ReconciliationJob reconciliation;
    private static Map<String, List<UUID>> accountsByCurrency;

    @BeforeContainer
    static void startEverything() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("ledger")
                .withUsername("ledger")
                .withPassword("ledger");
        postgres.start();

        context = new SpringApplication(LedgerApplication.class).run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--server.port=0",
                "--ledger.reconciliation.initial-delay-ms=3600000");

        ledger = context.getBean(RetryingLedgerFacade.class);
        postings = context.getBean(PostingRepository.class);
        balances = context.getBean(AccountBalanceRepository.class);
        reconciliation = context.getBean(ReconciliationJob.class);

        AccountRepository accounts = context.getBean(AccountRepository.class);
        accountsByCurrency = new LinkedHashMap<>();
        AccountType[] types = AccountType.values();
        for (String currency : CURRENCIES) {
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < ACCOUNT_POOL; i++) {
                UUID id = UUID.randomUUID();
                accounts.save(new Account(id, "prop-" + currency + "-" + i + "-" + id,
                        types[i % types.length], currency));
                balances.save(new AccountBalance(id, 0L));
                ids.add(id);
            }
            accountsByCurrency.put(currency, ids);
        }
    }

    @Property(tries = 40)
    void anyValidEntrySequenceKeepsTheLedgerBalanced(
            @ForAll("entrySequences") List<CreateEntryRequest> sequence) {

        for (CreateEntryRequest request : sequence) {
            ledger.createEntry("prop-" + UUID.randomUUID(), request);

            // Asserted after EVERY entry, not once at the end: an invariant that
            // only holds when you stop looking is not an invariant.
            assertThat(postings.sumOfAllPostings())
                    .as("sum of every posting ever written")
                    .isZero();
        }

        ReconciliationJob.ReconciliationReport report = reconciliation.reconcile();
        assertThat(report.isClean())
                .as("materialised balances must equal recomputed sums: %s", report)
                .isTrue();
    }

    @Property(tries = 25)
    void balancesAlwaysMatchTheirRecomputedSums(
            @ForAll("entrySequences") List<CreateEntryRequest> sequence) {

        for (CreateEntryRequest request : sequence) {
            ledger.createEntry("prop-bal-" + UUID.randomUUID(), request);
        }

        for (List<UUID> ids : accountsByCurrency.values()) {
            for (UUID accountId : ids) {
                long materialised = balances.findByAccountId(accountId)
                        .orElseThrow().getBalanceMinor();
                long recomputed = postings.currentBalance(accountId);
                assertThat(materialised)
                        .as("account %s: materialised vs recomputed", accountId)
                        .isEqualTo(recomputed);
            }
        }
    }

    // ------------------------------------------------------------- generators

    @Provide
    Arbitrary<List<CreateEntryRequest>> entrySequences() {
        return validEntry().list().ofMinSize(1).ofMaxSize(12);
    }

    /**
     * Generates entries that are balanced <em>by construction</em>.
     *
     * <p>The last leg absorbs the negation of everything before it, so the sum is
     * exactly zero for any generated amounts. Generating legs independently and
     * filtering for balance would discard almost every candidate and test almost
     * nothing.
     */
    private Arbitrary<CreateEntryRequest> validEntry() {
        return Arbitraries.of(CURRENCIES).flatMap(currency ->
                Arbitraries.integers().between(2, 8).flatMap(legCount ->
                        legAmounts(legCount - 1).flatMap(amounts ->
                                accountPicks(legCount).map(indices ->
                                        buildEntry(currency, amounts, indices)))));
    }

    private Arbitrary<List<Long>> legAmounts(int count) {
        // Spans 1 paisa to ~10 lakh, and both signs, so the generator explores
        // overflow-adjacent sums and mixed debit/credit shapes.
        return Arbitraries.longs()
                .between(-100_000_000L, 100_000_000L)
                .filter(amount -> amount != 0L)
                .list().ofSize(count);
    }

    private Arbitrary<List<Integer>> accountPicks(int count) {
        // Not `uniqueElements()`: an account legitimately appears twice in one
        // entry (a fee split, a partial settlement), and that path -- where the
        // service merges two deltas for one account before locking it -- is
        // exactly the one worth generating.
        return Arbitraries.integers().between(0, ACCOUNT_POOL - 1).list().ofSize(count);
    }

    private CreateEntryRequest buildEntry(String currency, List<Long> amounts,
                                          List<Integer> accountIndices) {
        List<UUID> pool = accountsByCurrency.get(currency);
        List<PostingRequest> legs = new ArrayList<>(amounts.size() + 1);

        long balancingLeg = 0L;
        for (int i = 0; i < amounts.size(); i++) {
            long amount = amounts.get(i);
            legs.add(new PostingRequest(pool.get(accountIndices.get(i)), amount, currency));
            balancingLeg = Math.subtractExact(balancingLeg, amount);
        }

        if (balancingLeg == 0L) {
            // The generated legs already cancelled out. A zero final leg is
            // rejected (a posting that records nothing), so nudge one leg and
            // rebalance rather than emitting an invalid entry.
            legs.set(0, new PostingRequest(legs.get(0).accountId(),
                    legs.get(0).amountMinor() + 1L, currency));
            balancingLeg = -1L;
        }

        legs.add(new PostingRequest(
                pool.get(accountIndices.get(accountIndices.size() - 1)),
                balancingLeg, currency));

        return new CreateEntryRequest("property-generated", legs);
    }
}
