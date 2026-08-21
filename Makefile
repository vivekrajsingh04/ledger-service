.PHONY: help build test unit invariant concurrency idempotency property api bench up down smoke load clean

MVN ?= mvn
BASE ?= http://localhost:8080

help:
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "};{printf "\033[36m%-14s\033[0m %s\n",$$1,$$2}'

build:  ## compile and package
	$(MVN) -B -ntp package -DskipTests

test:  ## full Testcontainers suite (needs Docker)
	$(MVN) -B -ntp test

unit:  ## pure unit tests, no container
	$(MVN) -B -ntp test -Dtest='CursorTest,RequestHasherTest'

invariant:  ## raw-SQL attempts to violate the accounting invariants
	$(MVN) -B -ntp test -Dtest='InvariantBypassTest'

concurrency:  ## 100 threads x 100 transfers, both strategies
	$(MVN) -B -ntp test -Dtest='HotAccountConcurrencyTest,OptimisticConcurrencyTest'

idempotency:  ## same key 50x concurrently
	$(MVN) -B -ntp test -Dtest='IdempotencyRaceTest'

property:  ## jqwik: random entry sequences keep SUM(postings) = 0
	$(MVN) -B -ntp test -Dtest='LedgerInvariantProperties'

api:  ## HTTP contract: status codes, problem+json, pagination
	$(MVN) -B -ntp test -Dtest='LedgerApiTest'

bench:  ## contention sweep for both strategies -> target/contention-benchmark.csv
	$(MVN) -B -ntp test -Dtest='ContentionBenchmarkTest' \
		-Dledger.bench=true -Dledger.concurrency=optimistic
	$(MVN) -B -ntp test -Dtest='ContentionBenchmarkTest' \
		-Dledger.bench=true -Dledger.concurrency=pessimistic
	@echo "results: target/contention-benchmark.csv"

up:  ## start postgres + the service
	docker compose up -d --build

down:
	docker compose down -v

smoke:  ## create, replay, conflict, reverse, as_of, paginate
	BASE=$(BASE) ./scripts/smoke.sh

load:  ## k6 load test; thresholds are assertions
	BASE=$(BASE) k6 run k6/load.js

clean:
	$(MVN) -B -ntp clean
