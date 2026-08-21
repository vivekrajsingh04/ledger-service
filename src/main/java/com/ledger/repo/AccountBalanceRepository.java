package com.ledger.repo;

import com.ledger.domain.AccountBalance;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountBalanceRepository extends JpaRepository<AccountBalance, UUID> {

    /**
     * Pessimistic read: {@code SELECT ... FOR UPDATE}.
     *
     * <p><b>Callers must pass account ids sorted ascending.</b> Two concurrent
     * transfers, A -> B and B -> A, that lock in the order they happen to mention
     * accounts will deadlock: the first holds A and waits for B while the second
     * holds B and waits for A. Postgres detects it and kills one after
     * {@code deadlock_timeout}, which turns a correctness bug into a latency
     * cliff. Locking in a total order -- any total order, as long as everyone
     * agrees -- makes the cycle impossible to form.
     *
     * <p>The ordering is applied in {@code PessimisticBalanceUpdater}, not left to
     * the caller's discretion.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM AccountBalance b WHERE b.accountId IN :ids ORDER BY b.accountId ASC")
    List<AccountBalance> lockAllOrdered(@Param("ids") List<UUID> idsSortedAscending);

    Optional<AccountBalance> findByAccountId(UUID accountId);

    /** Rows whose materialised balance disagrees with the sum of their postings. */
    @Query(value = """
            SELECT account_id, account_name, materialised_minor, recomputed_minor, drift_minor
              FROM balance_reconciliation
             WHERE drift_minor <> 0
            """, nativeQuery = true)
    List<Object[]> findDrift();

    @Query(value = "SELECT COALESCE(SUM(ABS(drift_minor)), 0) FROM balance_reconciliation",
            nativeQuery = true)
    long totalAbsoluteDrift();
}
