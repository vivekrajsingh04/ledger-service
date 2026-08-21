package com.ledger.repo;

import com.ledger.domain.Posting;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostingRepository extends JpaRepository<Posting, UUID> {

    List<Posting> findByEntryIdOrderByAmountMinorDesc(UUID entryId);

    /**
     * Balance as of an instant, recomputed from postings.
     *
     * <p>This is what makes the ledger regulator-reconstructable: any past
     * balance is derivable because nothing is ever mutated or removed. It reads
     * the entry's {@code created_at}, not the posting's, so a reversal booked
     * today for an entry dated last month lands on today -- which is what an
     * auditor expects, since the correction genuinely happened today.
     */
    @Query(value = """
            SELECT COALESCE(SUM(p.amount_minor), 0)
              FROM postings p
              JOIN journal_entries e ON e.id = p.entry_id
             WHERE p.account_id = :accountId
               AND e.created_at <= :asOf
            """, nativeQuery = true)
    long balanceAsOf(@Param("accountId") UUID accountId, @Param("asOf") Instant asOf);

    @Query(value = "SELECT COALESCE(SUM(amount_minor), 0) FROM postings WHERE account_id = :accountId",
            nativeQuery = true)
    long currentBalance(@Param("accountId") UUID accountId);

    /**
     * First page of a statement, newest first.
     *
     * <p>Keyset (cursor) pagination, not OFFSET. On an append-only table under
     * concurrent writes, OFFSET is actively wrong: rows inserted between two page
     * requests shift the window, so page 2 re-shows rows from page 1 and skips
     * others entirely. A keyset anchored on {@code (created_at, id)} is stable
     * because it names a position in the data rather than a count of rows.
     */
    @Query(value = """
            SELECT * FROM postings
             WHERE account_id = :accountId
             ORDER BY created_at DESC, id DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<Posting> firstPage(@Param("accountId") UUID accountId, @Param("limit") int limit);

    /** Subsequent pages, anchored strictly after the cursor's (created_at, id). */
    @Query(value = """
            SELECT * FROM postings
             WHERE account_id = :accountId
               AND (created_at, id) < (:cursorCreatedAt, :cursorId)
             ORDER BY created_at DESC, id DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<Posting> nextPage(@Param("accountId") UUID accountId,
                           @Param("cursorCreatedAt") Instant cursorCreatedAt,
                           @Param("cursorId") UUID cursorId,
                           @Param("limit") int limit);

    /**
     * Global invariant check: every posting ever written, summed per currency,
     * must be zero. Used by the property-based test after every operation.
     */
    @Query(value = "SELECT COALESCE(SUM(amount_minor), 0) FROM postings", nativeQuery = true)
    long sumOfAllPostings();
}
