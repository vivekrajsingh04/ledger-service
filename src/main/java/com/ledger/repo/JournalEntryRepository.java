package com.ledger.repo;

import com.ledger.domain.JournalEntry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    Optional<JournalEntry> findByIdempotencyKey(String idempotencyKey);

    Optional<JournalEntry> findByReversesEntryId(UUID reversesEntryId);

    /**
     * Atomically claims an idempotency key.
     *
     * <p>Returns 1 if this caller won the race and should go on to write postings,
     * 0 if the key already existed. This is the whole concurrency story for
     * idempotency, and it relies on two Postgres behaviours:
     *
     * <ol>
     *   <li>{@code ON CONFLICT DO NOTHING} against a UNIQUE index makes a
     *       conflicting INSERT <em>block</em> until the transaction holding the
     *       key commits or rolls back -- so a loser never proceeds on a
     *       half-written entry.</li>
     *   <li>Under READ COMMITTED every statement takes a fresh snapshot, so the
     *       loser's subsequent SELECT sees the winner's committed row. No polling
     *       loop, no sleep, no lost update.</li>
     * </ol>
     *
     * <p>If the winner rolls back, the conflicting INSERT proceeds normally and
     * that caller becomes the new winner -- a failed request must not burn a key.
     */
    @Modifying
    @Query(value = """
            INSERT INTO journal_entries
                (id, idempotency_key, request_hash, description, reverses_entry_id, created_at)
            VALUES (:id, :key, :hash, :description, :reverses, :createdAt)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int claimIdempotencyKey(@Param("id") UUID id,
                            @Param("key") String idempotencyKey,
                            @Param("hash") String requestHash,
                            @Param("description") String description,
                            @Param("reverses") UUID reversesEntryId,
                            @Param("createdAt") Instant createdAt);

    @Modifying
    @Query(value = """
            UPDATE journal_entries
               SET response_snapshot = CAST(:snapshot AS jsonb)
             WHERE id = :id
            """, nativeQuery = true)
    int saveResponseSnapshot(@Param("id") UUID id, @Param("snapshot") String snapshot);
}
