package com.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An append-only journal entry.
 *
 * <p>Deliberately has no setters beyond the response snapshot, and no
 * {@code @OneToMany} to postings. Postings are loaded explicitly through their
 * repository: an entity graph that can cascade-delete children is exactly the
 * kind of convenience that makes an "append-only" claim untrue in practice. The
 * database enforces the same rule with a BEFORE UPDATE OR DELETE trigger.
 */
@Entity
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    /**
     * JSON body returned the first time this idempotency key was accepted.
     *
     * <p>Written exactly once, by a targeted native UPDATE in the same
     * transaction that created the entry -- not through a JPA setter, because a
     * dirty-checked update would rewrite every column and there is deliberately
     * no code path in this class that can change a committed entry. The database
     * trigger permits this single NULL -> value transition and nothing else.
     */
    @Column(name = "response_snapshot", columnDefinition = "jsonb")
    private String responseSnapshot;

    @Column(name = "reverses_entry_id")
    private UUID reversesEntryId;

    @Column(nullable = false)
    private String description = "";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected JournalEntry() {
        // JPA
    }

    public JournalEntry(UUID id, String idempotencyKey, String requestHash,
                        String description, UUID reversesEntryId, Instant createdAt) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.description = description == null ? "" : description;
        this.reversesEntryId = reversesEntryId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getResponseSnapshot() {
        return responseSnapshot;
    }

    public UUID getReversesEntryId() {
        return reversesEntryId;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
