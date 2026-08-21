# Double-entry ledger microservice

**An append-only double-entry ledger that stays correct under concurrent writes, with idempotent APIs and DB-enforced accounting invariants.**

Sounds boring. It is boring. That's the point — correctness under concurrency is the entire job, and the tests are the deliverable.

---

## The invariant, enforced in the database

`SUM(amount_minor) = 0` for every entry, per currency. Checked in Java *and* enforced by Postgres:

```sql
CREATE CONSTRAINT TRIGGER entry_must_balance
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_entry_balances();
```

`DEFERRABLE INITIALLY DEFERRED` is what makes this work: the trigger fires at **COMMIT**, by which time every posting of the entry is present. A non-deferred trigger would reject the first leg of a perfectly valid two-leg entry.

This is **defence in depth**. The Java check exists to produce a precise 422 naming the imbalance; the trigger exists to make the guarantee *true* — against a service bug, a migration script, or anyone with a psql connection. [`InvariantBypassTest`](src/test/java/com/ledger/invariant/InvariantBypassTest.java) proves it by attacking the database directly with raw SQL, bypassing the application entirely.

Immutability is enforced the same way — `BEFORE UPDATE OR DELETE` triggers on both `postings` and `journal_entries` reject the statement outright.

### Money is `BIGINT` minor units

Never `double`, never `float`, never a `NUMERIC` whose scale we might get wrong. `0.1 + 0.2 != 0.3` in binary floating point, so sums drift, and a ledger whose totals drift is not a ledger. The type is `long` in Java and `BIGINT` in Postgres, end to end, and `AccountBalance.applyDelta` uses `Math.addExact` so overflow throws rather than silently wrapping a balance negative.

One non-obvious trap, closed explicitly: Jackson's default `ACCEPT_FLOAT_AS_INT` accepts a JSON float for a `long` field and **truncates it**. `{"amountMinor": 10.55}` would silently become `10` — 55 paise gone, no error, nowhere. It is disabled in `application.yml`, and [`LedgerApiTest.decimalAmountsAreRejected`](src/test/java/com/ledger/api/LedgerApiTest.java) asserts a decimal amount is a 400.

---

## Idempotency (done properly)

Clients send an `Idempotency-Key` header. Three cases, and the third is the one everyone forgets:

| Case | Behaviour |
| --- | --- |
| Key unseen | Process, store the entry **and a snapshot of the response** |
| Key seen, **same** request hash | Return the stored original response, **200** — not an error |
| Key seen, **different** request hash | **409 Conflict** |

Reference: [Stripe's idempotency contract](https://docs.stripe.com/api/idempotent_requests). Case 3 is loud on purpose. Reusing a key with a different payload is unambiguously a client bug — a key-generation collision, or a retry that mutated the body. Returning the original response would silently swallow it: the client thinks they moved ₹9,990, we moved ₹100, and nothing anywhere says otherwise.

### The race, and why there is no polling loop

50 concurrent requests with the same key must produce exactly one entry. The claim is a single statement:

```sql
INSERT INTO journal_entries (...) VALUES (...) ON CONFLICT (idempotency_key) DO NOTHING
```

Two Postgres behaviours make this sufficient, and both are worth naming:

1. A conflicting `INSERT ... ON CONFLICT DO NOTHING` **blocks** on the unique index until the transaction holding that key commits or rolls back. A loser can never proceed against a half-written entry.
2. Under READ COMMITTED, **every statement takes a fresh snapshot**, so the loser's subsequent `SELECT` sees the winner's committed row. No retry loop, no sleep, no lost update.

And if the winner *rolls back*, the blocked insert proceeds normally and that caller becomes the new winner — a failed request must not burn a key the client can never reuse. [`IdempotencyRaceTest`](src/test/java/com/ledger/idempotency/IdempotencyRaceTest.java) asserts all of it, including that all 50 callers receive the *same* entry id.

Canonical hashing: postings are sorted before hashing, so a client whose JSON library reorders a list is retrying, not sending a new request.

---

## Concurrency — both built, both benchmarked

### Optimistic — `@Version` + bounded exponential backoff with jitter

Hibernate appends `WHERE version = ?` to the balance UPDATE; a mismatch means someone got there first and raises `ObjectOptimisticLockingFailureException`.

**The retry is not in the service.** Once a transaction hits a lock failure it is marked rollback-only — every subsequent statement in it fails too, so retrying in place accomplishes nothing. The retry lives in [`RetryingLedgerFacade`](src/main/java/com/ledger/service/RetryingLedgerFacade.java), a separate non-transactional bean that re-enters a fresh transaction. It is a separate *class*, not a private method, because a self-invocation would bypass the Spring proxy and silently run without a new transaction at all. This is the single most common optimistic-locking bug.

Backoff uses **full jitter**: `sleep(random(0, base × 2^attempt))`. Without jitter, N transactions that conflict at the same instant sleep identical durations and collide again on every retry — a synchronised herd that converts contention into livelock.

> **Optimistic does not mean lock-free — and this cost me a build.** I originally applied balance deltas in map-iteration order here, reasoning that with no `SELECT FOR UPDATE` there was nothing to deadlock. That is wrong. There is no *explicit* lock, but every `UPDATE` takes a row lock held until the transaction ends, so two transactions touching the same pair of accounts in opposite orders deadlock exactly as they would under pessimistic locking.
>
> The 100-thread hot-account test produced **777 deadlocks** on the optimistic path while the pessimistic path — which already sorted — passed the identical test untouched. The optimistic updater now sorts ascending and uses `saveAndFlush`, because sorting the iteration alone is not enough: Hibernate otherwise defers the UPDATEs to flush and is free to emit them in an order of its own.
>
> Deterministic lock ordering is a property of **both** strategies, not a pessimistic-only concern. `bidirectionalTransfersDoNotDeadlock` is the test that caught it.

### Pessimistic — `SELECT ... FOR UPDATE` with deterministic lock ordering

```java
// Sort here, not at the call site: a lock ordering that depends on a caller
// remembering to sort is a lock ordering that will eventually be violated.
List<UUID> ordered = new ArrayList<>(deltas.keySet());
ordered.sort(UUID::compareTo);
```

Why it matters, concretely. Two concurrent transfers between the same pair, A→B and B→A, locking in request order:

```
txn1: LOCK A ......... waits for B
txn2: LOCK B ......... waits for A
```

That is a cycle in the wait-for graph — a textbook deadlock. Postgres detects it after `deadlock_timeout` and aborts one transaction with SQLSTATE 40P01. Nothing is corrupted, but a correctness bug has become a one-second latency cliff that only appears under load. Sorting gives every transaction the same total order, so no cycle can form. [`HotAccountConcurrencyTest.bidirectionalTransfersDoNotDeadlock`](src/test/java/com/ledger/concurrency/HotAccountConcurrencyTest.java) drives exactly this scenario with 40 threads split both ways.

### Benchmark

> Regenerate with `mvn test -Dtest=ContentionBenchmarkTest -Dledger.bench=true -Dledger.concurrency=optimistic` (then again with `pessimistic`). Results land in `target/contention-benchmark.csv`.

| Conflict rate | Optimistic (ops/sec) | Pessimistic (ops/sec) | Optimistic p99 | Pessimistic p99 |
| --- | --- | --- | --- | --- |
| 0% | _run the benchmark_ | | | |
| 10% | | | | |
| 25% | | | | |
| 40% | | | | |
| 60% | | | | |
| 100% | | | | |

> **Left empty deliberately.** These are measurements from a specific machine, and this repository has not run them on yours. The benchmark is written, parameterised across six conflict rates, and it writes a CSV — run it and paste the numbers. Reporting a plausible-looking table you didn't measure is exactly the credibility problem this project is built to avoid.

The shape to expect, and the thing to explain when the numbers are in: optimistic wins at low contention because it holds no locks and pays nothing when nothing conflicts; it degrades as the conflict rate climbs because every conflict means work already done is thrown away and redone. Pessimistic pays a fixed lock cost on every write and that cost doesn't grow with contention, so it stays flat. There is a crossover, and its location is the interesting number.

### What the crossover actually looks like

I did not have to reason about where optimistic gives out — the test suite showed me, and the numbers are not close:

| | Optimistic | Pessimistic |
| --- | --- | --- |
| 100 threads x 100 transfers on **one** row | 15,216 version conflicts; 88 requests exhausted 25 retries apiece | passes, no retries needed |
| 60 threads x 40 transfers across **30** accounts | exact, zero give-ups | (not the case it is for) |

The mechanism is worth stating plainly, because it is why no retry budget rescues it: the winning transaction's `UPDATE ... WHERE version = N` holds the row lock until commit, so all other writers block, then wake to find version `N+1` and fail. That is roughly 99 conflicts per successful commit at 100 writers, and raising the retry budget from 8 to 25 only raised the conflict count from 2,205 to 15,216.

So the hot-account exactness test runs under **pessimistic**, which is the correct strategy for that shape of traffic and the reason both are shipped. [`OptimisticConcurrencyTest`](src/test/java/com/ledger/concurrency/OptimisticConcurrencyTest.java) then pins two things about the optimistic path: it is **exact** at the contention it is designed for, and at pathological contention it **degrades safely** — some requests get an explicit `503 Retry-After`, and the final balance equals exactly the work that committed. Shedding throughput is acceptable; shedding money is not.

### Isolation levels

**READ COMMITTED is the default**, and deliberately so. It is Postgres's default, it takes no predicate locks, and it never fails a transaction with a serialization error — so throughput does not collapse when several writers touch the same account.

The one anomaly it permits that would matter here is a **lost update** on `account_balances`: two transactions read the same balance, both add, and one write is silently overwritten. That is closed *specifically*, by the `@Version` check or the row lock, rather than by escalating every transaction in the service to a stricter level.

**SERIALIZABLE** would also close it — Postgres's SSI would detect the read-write dependency cycle and abort one transaction. The trade is that on a hot account, that abort rate rises with concurrency: every serialization failure is wasted work plus a retry, and the failures cluster exactly where traffic is heaviest. Paying that on *every* transaction to fix an anomaly that affects *one table* is the wrong trade at this scale. I'd revisit it if the ledger grew invariants spanning multiple rows that no single lock can protect — a per-account credit limit checked across concurrent entries, say, which is precisely the read-write skew SSI exists for and which neither a version column nor a row lock can express.

---

## Immutability + reversal

**No posting is ever `UPDATE`d or `DELETE`d.** A correction is a new entry with mirrored postings, linked by `reverses_entry_id`, and an entry can be reversed at most once (enforced by a partial unique index, so two concurrent reversals cannot both succeed).

The reason is **audit trail and regulatory reconstructability**: a regulator must be able to reproduce the balance as of any past timestamp and get the number the business actually acted on at the time. Mutating a posting destroys that — the past silently changes, and every report ever produced from it becomes unreproducible. Booking a reversal keeps both facts: what was recorded, and what corrected it.

That's what makes this endpoint possible at all:

```
GET /v1/accounts/{id}/balance?as_of=2026-03-01T00:00:00Z
```

computed by summing postings up to that instant.

---

## API

```
POST   /v1/entries                    Idempotency-Key required
POST   /v1/entries/{id}/reverse       Idempotency-Key required
GET    /v1/entries/{id}
GET    /v1/accounts/{id}/balance?as_of=
GET    /v1/accounts/{id}/statement    cursor pagination
```

Errors are **RFC 7807** `application/problem+json`, every one with a stable `type` URI so clients branch on the type rather than string-matching a message, and every one carrying the request's `traceId`.

**Cursor pagination, not OFFSET.** On an append-only table under concurrent writes, OFFSET is actively wrong: rows inserted between two page requests shift the window, so page 2 re-shows rows from page 1 and skips others entirely. A keyset cursor over `(created_at, id)` names a *position in the data* rather than a *count of rows*, so it stays correct while the table grows underneath it. The cursor is base64-encoded and opaque so clients cannot come to depend on the sort key. It encodes microseconds, matching Postgres `timestamptz` — a nanosecond-precision cursor would point at an instant no row can have, and would skip or repeat the row at every page boundary.

---

## Tests — the actual deliverable

Testcontainers-backed real Postgres throughout. **H2 would be faster and would prove nothing**: the behaviour under test *is* Postgres behaviour — deferred constraint triggers, `SELECT ... FOR UPDATE` blocking semantics, `ON CONFLICT DO NOTHING` waiting on an uncommitted key, deadlock detection, READ COMMITTED statement snapshots. An in-memory database emulates none of it, so a green suite on H2 would be actively misleading.

| Test | What it asserts |
| --- | --- |
| [`HotAccountConcurrencyTest`](src/test/java/com/ledger/concurrency/HotAccountConcurrencyTest.java) | **100 threads × 100 transfers** against one hot account, released simultaneously by a latch, under the pessimistic strategy. Final balance exactly correct, **zero lost updates**, reconciliation clean. Plus 40 threads of bidirectional A↔B transfers with zero deadlock aborts. |
| [`OptimisticConcurrencyTest`](src/test/java/com/ledger/concurrency/OptimisticConcurrencyTest.java) | Optimistic path: **exact** across 30 accounts under 60 threads; and under one-row contention, **degrades safely** — every failure is an explicit 503, and the balance equals exactly the work that committed. |
| [`IdempotencyRaceTest`](src/test/java/com/ledger/idempotency/IdempotencyRaceTest.java) | Same key fired **50× concurrently** → exactly one entry, and all 50 callers get the *same* id. Plus: replay is byte-identical, reordered postings still replay, different body is 409, and a *failed* request does not burn its key. |
| [`LedgerInvariantProperties`](src/test/java/com/ledger/property/LedgerInvariantProperties.java) | **jqwik.** Random valid entry sequences — 2–8 legs, amounts spanning six orders of magnitude, multiple currencies, the same account on both sides of one entry. Asserts `SUM(all postings) == 0` **after every single operation**, and shrinks any failure to a minimal reproduction. |
| [`ReconciliationTest`](src/test/java/com/ledger/invariant/ReconciliationTest.java) | Materialised `account_balances` equals the recomputed sum from postings. Also **injects drift and asserts the job detects it** and exports the gauge — a reconciliation never tested against a known-bad state is a reconciliation nobody has tested. |
| [`InvariantBypassTest`](src/test/java/com/ledger/invariant/InvariantBypassTest.java) | Attacks the database with **raw SQL**, bypassing the service entirely: unbalanced entry rejected at COMMIT, single-posting entry rejected, zero amount rejected, `UPDATE`/`DELETE` on a posting rejected, committed entry cannot be rewritten. |
| [`LedgerApiTest`](src/test/java/com/ledger/api/LedgerApiTest.java) | HTTP contract: 201/200/409/422/400 status codes, problem+json media type, trace-id propagation, cursor pages that don't overlap, decimal amounts rejected. |
| `CursorTest`, `RequestHasherTest` | Pure unit tests, no container: cursor round-trips (including pre-epoch), hashing ignores posting order but not amounts. |

The reconciliation check also runs as a **scheduled job** in production, exporting drift to Prometheus:

- `ledger_reconciliation_drift_total` — sum of |drift| in minor units. **Alert on `> 0`**, not on a threshold: any drift at all is a bug.
- `ledger_reconciliation_drift_accounts` — how many accounts disagree.
- `ledger_postings_global_sum` — must be exactly `0` forever.

---

## Running it

```bash
docker compose up -d --build
./scripts/smoke.sh                 # create, replay, conflict, reverse, as_of, paginate
k6 run k6/load.js                  # p99 thresholds are assertions; k6 exits non-zero

mvn test                           # full Testcontainers suite (needs Docker)
mvn test -Dtest=ContentionBenchmarkTest -Dledger.bench=true
```

Swagger UI: <http://localhost:8080/swagger-ui.html> · OpenAPI JSON: <http://localhost:8080/v3/api-docs> · Metrics: <http://localhost:8080/actuator/prometheus>

Switch strategy with `LEDGER_CONCURRENCY=pessimistic docker compose up -d`.

### Load-test results

Measured, not estimated. Regenerate with `k6 run k6/load.js`; thresholds in the script are assertions, so a regression fails CI rather than quietly appearing in a table.

**Hardware: a 2-vCPU GitHub Actions runner with Postgres, the service and k6 all co-located on one box.** That is a deliberately unflattering environment — the load generator competes with the database it is measuring — and the numbers should be read as a floor, not a benchmark. The tail varies by roughly 2.5× between runs on that hardware (p95 ranged 328–844 ms), which is why CI gates on the correctness assertions tightly and the latency thresholds loosely.

| Metric | Measured |
| --- | --- |
| Writes completed | 57,587 over a 3-minute ramp to 50 VUs |
| Throughput | ~320 writes/sec |
| Success rate | 99.97% (57,574 / 57,587) |
| p50 write latency | **6.4 ms** |
| p90 | 137.5 ms |
| p95 | 327.8 ms (328–844 ms across runs) |
| p99 | 956.8 ms |
| **Reconciliation drift after the run** | **0** — asserted in k6 teardown |
| **Sum of all postings after the run** | **0** — asserted in k6 teardown |

The distribution is the interesting part: a 6.4 ms median against a 957 ms p99 is the optimistic strategy's signature. Most writes touch uncontended rows and complete immediately; the tail is the ~30% of traffic aimed at one hot account, where conflicts force retries. Switching that workload to `LEDGER_CONCURRENCY=pessimistic` trades the median up and the tail down — which is the whole argument for shipping both.

The two teardown assertions matter more than the latencies. A load test that is fast because it quietly dropped writes is a failed load test, so the run ends by checking the ledger still reconciles to zero drift and every posting ever written still sums to zero.

---

## Extras

- **Flyway migrations** — the schema, including the constraint trigger, is versioned and applied identically in dev, test and CI. Hibernate is `ddl-auto: validate`; it never touches the schema, because it could not express the trigger and would drop it.
- **OpenAPI generated from annotations** — no hand-maintained spec file to drift from the code.
- **Structured JSON logs** with a `traceId` per request (Logstash encoder + MDC filter). An inbound `X-Trace-Id` is propagated rather than replaced, so a trace started upstream survives the hop.
- **Micrometer / Prometheus** — every metric tagged with the active concurrency strategy, which makes the optimistic-vs-pessimistic comparison one query instead of two runs someone has to remember to label.
- **k6 load test** with p99 thresholds *and* a teardown that asserts the ledger reconciled to zero drift — a run that is fast because it dropped writes is a failed run.

---

## Scope guard — explicitly cut

Built in 2 weeks. Deliberately **not** built:

- **Auth.** Orthogonal to every correctness property here. A `@PreAuthorize` on each controller method would add lines and prove nothing about concurrency.
- **Any UI.** The OpenAPI spec and Swagger UI are the interface.
- **Event sourcing / CQRS.** The ledger is *already* an append-only event log with a materialised read model — that is what `postings` + `account_balances` + the reconciliation job are. Adopting the framing wholesale would add an event bus and projection lag without changing a single guarantee.
- **Multi-currency FX.** Entries balance *per currency*; converting between them is a pricing problem, not a ledger problem, and it belongs in the layer that decides the rate.

### Next step: the transactional outbox

Publishing ledger events to Kafka is the obvious next feature, and the obvious naive implementation is wrong: writing to Postgres and then publishing to Kafka is a **dual write**. There is no transaction spanning both, so a crash between them leaves the ledger and the event stream permanently inconsistent — and for a ledger, "we debited the account but never told anyone" is the bad direction.

The **transactional outbox pattern** fixes it: insert the event into an `outbox` table *in the same transaction as the postings*, so it commits atomically with the ledger write or not at all. A separate relay (a poller, or Debezium reading the WAL via change data capture) publishes from that table to Kafka and marks rows sent. Delivery becomes at-least-once with an idempotent consumer, which is a solved problem — as opposed to dual-write inconsistency, which is not.

I have not built it. Naming it correctly is the point: it signals I know why the naive version is broken without spending a week proving it.

---

## Layout

```
src/main/java/com/ledger/
  domain/          Account, JournalEntry, Posting, AccountBalance (@Version)
  repo/            keyset pagination, as-of balance, FOR UPDATE, ON CONFLICT claim
  service/
    LedgerService              write path + the three-way idempotency contract
    RetryingLedgerFacade       retry OUTSIDE the transaction, full jitter
    OptimisticBalanceUpdater   @Version
    PessimisticBalanceUpdater  SELECT FOR UPDATE, sorted lock order
    ReconciliationJob          scheduled drift check -> Prometheus gauge
  api/             controllers + RFC 7807 handler
src/main/resources/db/migration/
  V1__baseline.sql   deferred constraint trigger, immutability triggers
src/test/java/com/ledger/
  concurrency/  idempotency/  property/  invariant/  api/
```
