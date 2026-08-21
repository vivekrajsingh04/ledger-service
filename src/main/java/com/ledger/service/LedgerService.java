package com.ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.api.dto.CreateEntryRequest;
import com.ledger.api.dto.EntryResponse;
import com.ledger.api.dto.PostingRequest;
import com.ledger.domain.JournalEntry;
import com.ledger.domain.Posting;
import com.ledger.error.AlreadyReversedException;
import com.ledger.error.IdempotencyConflictException;
import com.ledger.error.InvalidRequestException;
import com.ledger.error.NotFoundException;
import com.ledger.error.UnbalancedEntryException;
import com.ledger.repo.AccountRepository;
import com.ledger.repo.JournalEntryRepository;
import com.ledger.repo.PostingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger's write path.
 *
 * <p>Isolation is READ COMMITTED -- Postgres's default, and the right one here.
 * The one anomaly it permits that would matter to us is a lost update on
 * {@code account_balances}, and that is closed explicitly by {@link BalanceUpdater}
 * (a version check or a row lock) rather than by escalating every transaction to
 * SERIALIZABLE. See the README's isolation-levels note.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final JournalEntryRepository entries;
    private final PostingRepository postings;
    private final AccountRepository accounts;
    private final BalanceUpdater balanceUpdater;
    private final RequestHasher hasher;
    private final ObjectMapper mapper;

    public LedgerService(JournalEntryRepository entries,
                         PostingRepository postings,
                         AccountRepository accounts,
                         BalanceUpdater balanceUpdater,
                         RequestHasher hasher,
                         ObjectMapper mapper) {
        this.entries = entries;
        this.postings = postings;
        this.accounts = accounts;
        this.balanceUpdater = balanceUpdater;
        this.hasher = hasher;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ create

    /**
     * Creates an entry, or replays the stored response if the key was seen before.
     *
     * <p>The three-way idempotency contract, in order:
     * <ol>
     *   <li>key unseen -> process, store the entry and a snapshot of the response;</li>
     *   <li>key seen, same request hash -> return the stored response with 200;</li>
     *   <li>key seen, different request hash -> 409.</li>
     * </ol>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IdempotentResult<EntryResponse> createEntry(String idempotencyKey,
                                                       CreateEntryRequest request) {
        validate(request);
        String requestHash = hasher.hash(request);

        UUID entryId = UUID.randomUUID();
        Instant now = Instant.now();
        int claimed = entries.claimIdempotencyKey(
                entryId, idempotencyKey, requestHash, safeDescription(request.description()),
                null, now);

        if (claimed == 0) {
            return replay(idempotencyKey, requestHash);
        }

        List<Posting> written = writePostings(toPostings(entryId, request, now));
        JournalEntry entry = entries.findById(entryId).orElseThrow(
                () -> new IllegalStateException("entry vanished after claim: " + entryId));

        EntryResponse response = EntryResponse.from(entry, written);
        entries.saveResponseSnapshot(entryId, serialise(response));

        log.info("entry created id={} postings={} strategy={}",
                entryId, written.size(), balanceUpdater.strategyName());
        return IdempotentResult.created(response);
    }

    // ----------------------------------------------------------------- reverse

    /**
     * Reverses an entry by writing a <em>new</em> entry with mirrored postings.
     *
     * <p>Nothing is updated and nothing is deleted. The original entry stays
     * exactly as it was booked, and the correction is a separate, dated fact
     * linked by {@code reverses_entry_id}.
     *
     * <p>The reason is not aesthetic. An audit trail has to be reconstructable:
     * a regulator must be able to ask what the balance was on any past date and
     * get the answer the business actually acted on at that time. Mutating the
     * original posting destroys that -- the past silently changes, and every
     * report ever produced from it becomes unreproducible. Booking a reversal
     * keeps both facts: what was recorded, and what corrected it.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IdempotentResult<EntryResponse> reverseEntry(String idempotencyKey, UUID originalId) {
        JournalEntry original = entries.findById(originalId)
                .orElseThrow(() -> new NotFoundException("Entry", originalId));

        entries.findByReversesEntryId(originalId).ifPresent(existing -> {
            throw new AlreadyReversedException(originalId, existing.getId());
        });

        String requestHash = hasher.hashReversal(originalId.toString());
        UUID reversalId = UUID.randomUUID();
        Instant now = Instant.now();

        int claimed = entries.claimIdempotencyKey(
                reversalId, idempotencyKey, requestHash,
                "reversal of " + originalId, originalId, now);

        if (claimed == 0) {
            return replay(idempotencyKey, requestHash);
        }

        List<Posting> mirrored = new ArrayList<>();
        for (Posting p : postings.findByEntryIdOrderByAmountMinorDesc(originalId)) {
            mirrored.add(new Posting(UUID.randomUUID(), reversalId, p.getAccountId(),
                    Math.negateExact(p.getAmountMinor()), p.getCurrency(), now));
        }
        if (mirrored.isEmpty()) {
            throw new IllegalStateException("entry " + originalId + " has no postings");
        }

        List<Posting> written = writePostings(mirrored);
        JournalEntry reversal = entries.findById(reversalId).orElseThrow(
                () -> new IllegalStateException("reversal vanished after claim"));

        EntryResponse response = EntryResponse.from(reversal, written);
        entries.saveResponseSnapshot(reversalId, serialise(response));

        log.info("entry reversed original={} reversal={}", originalId, reversalId);
        return IdempotentResult.created(response);
    }

    // ------------------------------------------------------------------ shared

    private List<Posting> writePostings(List<Posting> toWrite) {
        List<Posting> saved = postings.saveAll(toWrite);

        // One delta per account, so an entry that touches the same account twice
        // (perfectly legal -- a fee split, say) locks and updates it once.
        Map<UUID, Long> deltas = new LinkedHashMap<>();
        for (Posting p : saved) {
            deltas.merge(p.getAccountId(), p.getAmountMinor(), Math::addExact);
        }
        balanceUpdater.applyDeltas(deltas);

        // Force the deferred constraint trigger to run now rather than at commit,
        // so an unbalanced entry surfaces as an exception we can map to a 422
        // instead of a transaction that dies on the way out.
        postings.flush();
        return saved;
    }

    private IdempotentResult<EntryResponse> replay(String idempotencyKey, String requestHash) {
        JournalEntry existing = entries.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "idempotency key " + idempotencyKey + " was claimed by another "
                                + "transaction but is not visible; this should be "
                                + "impossible under READ COMMITTED after a blocking "
                                + "ON CONFLICT DO NOTHING"));

        if (!existing.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(idempotencyKey);
        }

        EntryResponse response = existing.getResponseSnapshot() != null
                ? deserialise(existing.getResponseSnapshot())
                : EntryResponse.from(existing, postings.findByEntryIdOrderByAmountMinorDesc(
                        existing.getId()));

        log.info("idempotent replay key={} entry={}", idempotencyKey, existing.getId());
        return IdempotentResult.replayed(response);
    }

    private List<Posting> toPostings(UUID entryId, CreateEntryRequest request, Instant now) {
        List<Posting> result = new ArrayList<>(request.postings().size());
        for (PostingRequest p : request.postings()) {
            if (accounts.findById(p.accountId()).isEmpty()) {
                throw new NotFoundException("Account", p.accountId());
            }
            result.add(new Posting(UUID.randomUUID(), entryId, p.accountId(),
                    p.amountMinor(), p.currency(), now));
        }
        return result;
    }

    /**
     * Application-level validation of the same invariant the database enforces.
     *
     * <p>This is not redundant, it is defence in depth with a purpose: checking
     * here produces a precise 422 naming the imbalance, which is a far better
     * client experience than a constraint violation at commit. The trigger is
     * what makes the guarantee true; this is what makes it usable.
     */
    private void validate(CreateEntryRequest request) {
        if (request.postings().size() < 2) {
            throw new InvalidRequestException(
                    "a double-entry entry needs at least two postings");
        }

        Map<String, Long> sums = new HashMap<>();
        for (PostingRequest p : request.postings()) {
            if (p.amountMinor() == 0) {
                throw new InvalidRequestException(
                        "posting amounts must be non-zero; a zero posting records nothing");
            }
            sums.merge(p.currency(), p.amountMinor(), Math::addExact);
        }

        Map<String, Long> imbalance = new LinkedHashMap<>();
        sums.forEach((currency, total) -> {
            if (total != 0L) {
                imbalance.put(currency, total);
            }
        });
        if (!imbalance.isEmpty()) {
            throw new UnbalancedEntryException(imbalance);
        }
    }

    private static String safeDescription(String description) {
        return description == null ? "" : description;
    }

    private String serialise(EntryResponse response) {
        try {
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot snapshot response", e);
        }
    }

    private EntryResponse deserialise(String snapshot) {
        try {
            return mapper.readValue(snapshot, EntryResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot read response snapshot", e);
        }
    }
}
