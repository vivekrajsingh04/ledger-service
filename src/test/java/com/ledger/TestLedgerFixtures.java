package com.ledger;

import com.ledger.api.dto.CreateEntryRequest;
import com.ledger.api.dto.PostingRequest;
import com.ledger.domain.Account;
import com.ledger.domain.AccountBalance;
import com.ledger.domain.AccountType;
import com.ledger.repo.AccountBalanceRepository;
import com.ledger.repo.AccountRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Account creation and entry-building helpers shared by the test suite. */
@Component
public class TestLedgerFixtures {

    private final AccountRepository accounts;
    private final AccountBalanceRepository balances;

    public TestLedgerFixtures(AccountRepository accounts, AccountBalanceRepository balances) {
        this.accounts = accounts;
        this.balances = balances;
    }

    @Transactional
    public UUID createAccount(String name, AccountType type, String currency) {
        UUID id = UUID.randomUUID();
        accounts.save(new Account(id, name + "-" + id, type, currency));
        balances.save(new AccountBalance(id, 0L));
        return id;
    }

    public UUID createAsset(String name) {
        return createAccount(name, AccountType.ASSET, "INR");
    }

    public UUID createLiability(String name) {
        return createAccount(name, AccountType.LIABILITY, "INR");
    }

    /** A balanced transfer: debit {@code to}, credit {@code from}. */
    public static CreateEntryRequest transfer(UUID from, UUID to, long amountMinor) {
        return new CreateEntryRequest(
                "transfer " + amountMinor,
                List.of(new PostingRequest(to, amountMinor, "INR"),
                        new PostingRequest(from, -amountMinor, "INR")));
    }

    /** Deliberately unbalanced, for invariant tests. */
    public static CreateEntryRequest unbalanced(UUID a, UUID b, long debit, long credit) {
        return new CreateEntryRequest(
                "unbalanced",
                List.of(new PostingRequest(a, debit, "INR"),
                        new PostingRequest(b, -credit, "INR")));
    }
}
