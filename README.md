# Mphasis Idempotent Transaction Processor

A Java 17 command-line batch service for processing local JSON transactions with deterministic sequence ordering, idempotency, safe retries, account/currency balances, and an immutable in-memory ledger. It is an assessment prototype, not a production financial ledger.

## Prerequisites

- Java 17 or newer (the project compiles with `--release 17`)
- Maven 3.8+

## Build, test, and run

From this directory:

```powershell
mvn clean test
mvn exec:java
```

An alternate JSON file can be supplied as the first application argument:

```powershell
mvn exec:java -Dexec.args="input/transactions.json"
```

The sample is [input/transactions.json](input/transactions.json). The JSON reader is behind `TransactionInputReader`; parsing errors in individual array items become rejected outcomes and do not stop the rest of the batch.

## Behavior and assumptions

- **Ordering:** a single global contiguous sequence starts at 1. Higher sequences wait until earlier slots resolve. This matches the assessment fixture, which sequences transactions across accounts. A valid unused sequence attached to a rejected record resolves its slot; a failed transaction blocks later sequences. Missing gaps and pending sequences appear in the report.
- **Idempotency:** `transactionId` is the business idempotency key. A repeat with identical immutable fields (transaction, account, type, amount, currency, sequence) is `DUPLICATE`; a conflicting payload is rejected with `IDEMPOTENCY_CONFLICT`. Delivery `requestId` is not part of the fingerprint.
- **Money and balances:** all amounts use `BigDecimal`. Balances are isolated by account and currency. CREDIT and DEBIT are supported; a debit that would overdraw is rejected as `INSUFFICIENT_FUNDS`. Reversals are explicitly unsupported.
- **Retry:** maximum 3 total attempts (the first attempt plus up to two retries), without sleeping. The sample deterministically fails `TXN-2005` on attempt 1 and succeeds on attempt 2. Retry exhaustion results in `FAILED` / `RETRY_EXHAUSTED` without a ledger or balance effect.
- **Reporting:** per-request outcomes include duplicate deliveries; a deferred request's final outcome is updated after it drains. Outcome counts are by final request outcome, while canonical-state counts are by unique transaction ID. Retry totals count attempts across canonical transactions.

## Sample behavior

The current verified sample run prints the following key values:

```text
Requests read: 6
Final balances:
  ACC-201/USD=85.00
  ACC-202/USD=0.00
  ACC-203/USD=500.00
Ledger entries: 4
```

The first `TXN-2002` delivery initially logs `TRANSACTION_PENDING`, then is processed after sequence 1 arrives. Its later delivery is `DUPLICATE`. `TXN-2004` is rejected with `INVALID_AMOUNT`; `TXN-2005` succeeds on its second attempt.

The complete sample output from the verified run is in [docs/sample-output.txt](docs/sample-output.txt).

## Run reports

The latest captured test and sample-run reports are committed under [docs/reports/](docs/reports/):

- [maven-test-run.txt](docs/reports/maven-test-run.txt) — full `mvn clean test` console output (12 tests, 0 failures/errors).
- [surefire/](docs/reports/surefire/) — Maven Surefire per-class text and machine-readable JUnit XML reports.
- [sample-run.txt](docs/reports/sample-run.txt) — full `mvn -q exec:java` sample output.

To refresh the captured reports after changing source or tests, run these PowerShell commands from the project root:

```powershell
mvn clean test *> docs\reports\maven-test-run.txt
New-Item -ItemType Directory -Force docs\reports\surefire | Out-Null
Copy-Item target\surefire-reports\TEST-*.xml docs\reports\surefire\
Copy-Item target\surefire-reports\*.txt docs\reports\surefire\
mvn -q exec:java *> docs\reports\sample-run.txt
```

## Limitations and production hardening

State is in memory and is lost on process exit. Synchronized store operations protect this single process only; they do not provide durability or distributed exactly-once processing. Global ordering can block unrelated accounts. The policy that rejected records consume valid sequence slots and the no-overdraft assumption require confirmation with the business/upstream owners.

Before production, persist transaction/idempotency state, balances, and ledger entries in one database transaction; use database uniqueness and concurrency control; integrate a durable queue through a replaceable adapter; persist retry schedule and attempt counts with exponential backoff and jitter; add dead-letter/manual recovery, gap monitoring, structured metrics/logs/alerts, secrets management, input limits, replay/reconciliation tooling, and database/queue concurrency tests.

## AI-assisted development disclosure

AI assistance was used to derive implementation tasks from the provided specification, create the Java project and tests, and review the sample behavior. The generated implementation was checked against the acceptance scenarios and verified with the commands above; see the test suite for executable evidence.
