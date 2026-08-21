package com.ledger.service;

import com.ledger.api.dto.BalanceResponse;
import com.ledger.api.dto.EntryResponse;
import com.ledger.api.dto.PostingResponse;
import com.ledger.api.dto.StatementPage;
import com.ledger.domain.Account;
import com.ledger.domain.Posting;
import com.ledger.error.InvalidRequestException;
import com.ledger.error.NotFoundException;
import com.ledger.repo.AccountBalanceRepository;
import com.ledger.repo.AccountRepository;
import com.ledger.repo.JournalEntryRepository;
import com.ledger.repo.PostingRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class QueryService {

    public static final int MAX_PAGE_SIZE = 500;
    public static final int DEFAULT_PAGE_SIZE = 50;

    private final AccountRepository accounts;
    private final AccountBalanceRepository balances;
    private final PostingRepository postings;
    private final JournalEntryRepository entries;

    public QueryService(AccountRepository accounts,
                        AccountBalanceRepository balances,
                        PostingRepository postings,
                        JournalEntryRepository entries) {
        this.accounts = accounts;
        this.balances = balances;
        this.postings = postings;
        this.entries = entries;
    }

    /**
     * Balance, optionally as of a past instant.
     *
     * <p>Two different reads on purpose. Without {@code asOf} we serve the
     * materialised balance, which is O(1). With {@code asOf} we recompute from
     * postings, because the materialised row only knows "now" -- and recomputing
     * is only possible at all because nothing is ever mutated or deleted.
     */
    public BalanceResponse balance(UUID accountId, Instant asOf) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account", accountId));

        boolean recomputed = asOf != null;
        Instant effectiveAsOf = asOf != null ? asOf : Instant.now();
        long balanceMinor = recomputed
                ? postings.balanceAsOf(accountId, asOf)
                : balances.findByAccountId(accountId)
                        .map(b -> b.getBalanceMinor())
                        .orElse(0L);

        long normal = account.getType().normalBalanceIsDebit()
                ? balanceMinor
                : Math.negateExact(balanceMinor);

        return new BalanceResponse(
                account.getId(), account.getName(), account.getType().name(),
                account.getCurrency(), balanceMinor, normal, effectiveAsOf, recomputed);
    }

    /** Cursor-paginated statement, newest first. */
    public StatementPage statement(UUID accountId, String cursor, Integer limit) {
        if (accounts.findById(accountId).isEmpty()) {
            throw new NotFoundException("Account", accountId);
        }
        int pageSize = resolvePageSize(limit);

        // Fetch one extra row to learn whether another page exists, without a
        // second COUNT query (which on an append-only table would be both slow
        // and immediately stale).
        List<Posting> rows = cursor == null || cursor.isBlank()
                ? postings.firstPage(accountId, pageSize + 1)
                : fetchAfter(accountId, Cursor.decode(cursor), pageSize + 1);

        boolean hasMore = rows.size() > pageSize;
        List<Posting> page = hasMore ? rows.subList(0, pageSize) : rows;

        String nextCursor = null;
        if (hasMore) {
            Posting last = page.get(page.size() - 1);
            nextCursor = Cursor.encode(last.getCreatedAt(), last.getId());
        }

        return new StatementPage(page.stream().map(PostingResponse::from).toList(),
                nextCursor, hasMore);
    }

    public EntryResponse entry(UUID entryId) {
        return entries.findById(entryId)
                .map(e -> EntryResponse.from(
                        e, postings.findByEntryIdOrderByAmountMinorDesc(entryId)))
                .orElseThrow(() -> new NotFoundException("Entry", entryId));
    }

    private List<Posting> fetchAfter(UUID accountId, Cursor cursor, int limit) {
        return postings.nextPage(accountId, cursor.createdAt(), cursor.id(), limit);
    }

    private static int resolvePageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new InvalidRequestException(
                    "limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        return limit;
    }
}
