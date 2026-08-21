package com.ledger;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for every integration test: a real Postgres in a container.
 *
 * <p>H2 would be faster and would prove nothing. The behaviour under test <em>is</em>
 * Postgres behaviour -- {@code DEFERRABLE INITIALLY DEFERRED} constraint triggers,
 * {@code SELECT ... FOR UPDATE} blocking semantics, {@code ON CONFLICT DO NOTHING}
 * waiting on an uncommitted unique key, deadlock detection, READ COMMITTED
 * statement-level snapshots. An in-memory database with a compatibility mode
 * emulates none of it, so a green suite on H2 would be actively misleading.
 *
 * <p>One container is shared across the whole suite via the singleton pattern:
 * per-class containers would add ~5s each, and every test cleans up after itself.
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractPostgresTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ledger")
                    .withUsername("ledger")
                    .withPassword("ledger")
                    // Deadlock detection default is 1s. Lowering it makes the
                    // pessimistic lock-ordering test fail fast when ordering is
                    // wrong, instead of appearing to hang.
                    .withCommand("postgres", "-c", "deadlock_timeout=200ms",
                            "-c", "max_connections=200");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The reconciliation job runs on a schedule in production; tests invoke
        // it directly so results are deterministic.
        registry.add("ledger.reconciliation.initial-delay-ms", () -> "3600000");
    }
}
