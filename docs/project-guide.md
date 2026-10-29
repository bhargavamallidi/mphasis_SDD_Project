# Project guide

## Purpose and scope

This project is a Java command-line prototype of an idempotent background transaction processor. It reads a JSON batch, validates each record, applies a global sequence order, suppresses duplicate deliveries, simulates deterministic transient failures, and maintains in-memory account/currency balances and a ledger.

The implementation favors a small set of explicit components over infrastructure frameworks. It does not include a database, message broker, web service, authentication, reversals, or durable state.

## Project layout and file significance

```text
mphasis-transaction-processor/
├── pom.xml
├── .gitignore
├── README.md
├── input/
│   └── transactions.json
├── docs/
│   ├── design.md
│   ├── project-guide.md
│   ├── sample-output.txt
│   ├── testing-guide.md
│   └── reports/
├── src/main/java/com/mphasis/txnprocessor/
│   ├── Main.java
│   ├── input/
│   │   ├── TransactionInputReader.java
│   │   └── JsonTransactionInputReader.java
│   ├── model/
│   │   ├── InputRecord.java
│   │   ├── LedgerEntry.java
│   │   ├── ProcessingOutcome.java
│   │   ├── ProcessingSummary.java
│   │   ├── TransactionRecord.java
│   │   ├── TransactionRequest.java
│   │   ├── TransactionStatus.java
│   │   └── TransactionType.java
│   ├── ordering/
│   │   └── SequenceOrderingService.java
│   ├── retry/
│   │   ├── ConfiguredFailureSimulator.java
│   │   ├── FailureSimulator.java
│   │   └── RetryPolicy.java
│   ├── service/
│   │   ├── ProcessingReportService.java
│   │   └── TransactionProcessorService.java
│   ├── store/
│   │   └── InMemoryTransactionStore.java
│   └── validation/
│       └── TransactionValidator.java
└── src/test/java/com/mphasis/txnprocessor/
    ├── JsonTransactionInputReaderTest.java
    ├── TransactionProcessorServiceTest.java
    └── TransactionValidatorTest.java
```

### Root and input files

- **`pom.xml`** — Maven coordinates, Java 17 release target, Jackson Databind, JUnit 5, compiler/Surefire plugins, and the exec plugin configured to launch `Main`.
- **`.gitignore`** — excludes generated Maven `target/` output and common IDE-specific files.
- **`README.md`** — quick setup, commands, assumptions, limitations, and high-level behavior.
- **`input/transactions.json`** — six-row assessment fixture. It deliberately sends sequence 2 before 1, repeats `TXN-2002`, includes a negative amount at sequence 4, and uses `TXN-2005` to demonstrate retry.

### Documentation files

- **`docs/design.md`** — architecture, invariants, business assumptions, limitations, and production-hardening path.
- **`docs/project-guide.md`** — this file: component reference and execution walkthrough.
- **`docs/testing-guide.md`** — test-by-test explanation and coverage boundaries.
- **`docs/sample-output.txt`** — concise captured output for the assessment batch.
- **`docs/reports/`** — captured Maven test transcript, Surefire results, and sample run.

### Application entry point

- **`Main.java`** — chooses `input/transactions.json` by default (or a user-supplied path), reads records, constructs the store, validator, retry policy and configured simulator, runs the batch, then prints the report. It is the composition root: business rules live in services, not in this class.

### Input adapter

- **`TransactionInputReader.java`** — replaceable input boundary. A future queue/API adapter can implement this contract without moving transaction rules into parsing code.
- **`JsonTransactionInputReader.java`** — reads a JSON array with Jackson and converts each object into an `InputRecord`. An invalid per-row shape or field type is represented as `INVALID_JSON_RECORD`; invalid business values such as negative amounts are parsed normally and later rejected by validation. A non-array root or unreadable/malformed document fails the input operation with `IOException`.

### Models

- **`InputRecord.java`** — holds either a parsed `TransactionRequest` or a safe parse error code for one input row.
- **`TransactionRequest.java`** — immutable input fields, normalization for business comparisons, and comparison of the immutable idempotency payload. `requestId` is excluded from that comparison; `BigDecimal` amounts compare numerically.
- **`TransactionType.java`** — supported financial operations: `CREDIT` and `DEBIT`.
- **`TransactionStatus.java`** — lifecycle/outcome labels: `RECEIVED`, `PROCESSING`, `PROCESSED`, `DUPLICATE`, `PENDING`, `RETRY_PENDING`, `REJECTED`, and `FAILED`.
- **`TransactionRecord.java`** — canonical transaction state, mutable status, attempt count, and last safe error code.
- **`LedgerEntry.java`** — immutable record of a successful transaction, including transaction/account identifiers, type, amount, currency, sequence, resulting balance, and timestamp.
- **`ProcessingOutcome.java`** — one final outcome per input delivery. This is separate from the canonical record so a processed transaction can have a later `DUPLICATE` delivery outcome.
- **`ProcessingSummary.java`** — aggregate request outcome counts, canonical transaction counts, total attempts, pending and missing sequences, and blocking sequence.

### Validation and ordering

- **`TransactionValidator.java`** — checks required values, positive amount, three-letter alphabetic currency, positive sequence, and supported transaction type. It returns explicit error codes rather than applying financial effects.
- **`SequenceOrderingService.java`** — maintains a cursor starting at sequence 1, higher-sequence pending records, and resolved sequence slots. It exposes gaps and advances past contiguous resolved slots.

### Retry and processing services

- **`RetryPolicy.java`** — holds and validates the maximum total attempt count, including the initial attempt.
- **`FailureSimulator.java`** — interface for deterministic processing failures; lets tests supply success/failure behavior without randomness.
- **`ConfiguredFailureSimulator.java`** — configures a number of failures before success per transaction ID. `Main` sets `TXN-2005` to fail once.
- **`TransactionProcessorService.java`** — coordinates per-row processing. Its order of work is idempotency, sequence ownership, validation, waiting/processing, retry, and status/outcome updates. It drains pending work when the cursor advances and computes summaries.
- **`ProcessingReportService.java`** — formats request outcomes, counts, attempts, sequence gaps/blocking, final balances, and ledger entries for the command line.

### Store

- **`InMemoryTransactionStore.java`** — owns canonical records, sequence owners, account/currency balances, and ledger entries. Synchronized methods protect mutations within this process. Its `commit` method calculates the proposed `BigDecimal` balance and rejects overdrafts before appending the ledger entry and updating balance/status.

### Tests

- **`JsonTransactionInputReaderTest.java`** — malformed array row isolation.
- **`TransactionValidatorTest.java`** — valid normalization and required-field, amount, currency, sequence, and type rules.
- **`TransactionProcessorServiceTest.java`** — assessment integration fixture plus idempotency, ordering, gap reporting, overdraft, retry exhaustion, sequence collision, currency separation, and invalid-slot behavior.

The individual test methods and their assertions are documented in [testing-guide.md](testing-guide.md).

## End-to-end execution flow

Run from the project root:

```powershell
mvn clean test
mvn exec:java
```

To select a different JSON file:

```powershell
mvn exec:java -Dexec.args="input/transactions.json"
```

The runtime flow is:

1. **Maven launches `Main`.** The exec plugin uses `com.mphasis.txnprocessor.Main`.
2. **Choose the input path.** No argument selects `input/transactions.json`; the first argument overrides it.
3. **Parse records.** `JsonTransactionInputReader` reads the JSON array and returns `InputRecord` values. A malformed row is retained as a parse-error outcome rather than suppressing sibling rows.
4. **Wire dependencies.** `Main` creates one `InMemoryTransactionStore`, a `TransactionValidator`, a three-total-attempt `RetryPolicy`, and a simulator configured to fail `TXN-2005` on attempt 1.
5. **Process each row.** `TransactionProcessorService` normalizes fields and checks whether `transactionId` is already known. An identical immutable payload is a `DUPLICATE`; changed business fields produce `IDEMPOTENCY_CONFLICT`.
6. **Claim and validate.** A new request claims its positive sequence if available. A different transaction ID that owns that sequence produces `SEQUENCE_CONFLICT`. Business validation then either rejects the row or allows ordering to continue.
7. **Order the transaction.** The expected global sequence begins at 1. Higher valid sequences enter `PENDING`. A request at the cursor is attempted. Successful processing or a definitive validation rejection resolves its sequence; the service then drains contiguous pending requests. A failed transaction keeps its sequence unresolved and blocks later sequence numbers.
8. **Retry and commit.** A processing attempt is passed to the deterministic simulator. Transient failure updates the state to `RETRY_PENDING` before another attempt. On a non-failing attempt, `InMemoryTransactionStore.commit` checks the resulting balance. If valid, it appends exactly one ledger entry, updates balance, and records `PROCESSED`; insufficient funds are rejected with no financial effect.
9. **Update delivery outcomes.** Each input row retains a `ProcessingOutcome`. Deferred rows are updated when they eventually process or fail. Duplicate deliveries get their own outcome without changing the original canonical record.
10. **Summarize and print.** `ProcessingReportService` prints per-input outcome and error code, request and canonical counts, attempts, gaps, blocking sequence, balances, and ledger entries.

## Walkthrough of the supplied batch

1. `TXN-2002` at sequence 2 arrives first. Since the cursor expects 1, it is retained as pending and the report can identify missing sequence 1.
2. `TXN-2001` at sequence 1 processes. The cursor advances, then the pending `TXN-2002` is drained and debits 40 from the newly credited 100.
3. The second delivery of `TXN-2002` has the same business payload, so it is reported as `DUPLICATE` and does not add another ledger entry.
4. `TXN-2003` at sequence 3 credits 25.
5. `TXN-2004` has amount -10. It is rejected with `INVALID_AMOUNT`, has no ledger effect, and resolves valid unused sequence 4 under the stated assumption.
6. `TXN-2005` at sequence 5 fails deterministically once, enters retry, then succeeds on attempt 2 and credits 500.
7. The report shows ACC-201/USD 85.00, ACC-202/USD 0.00, ACC-203/USD 500.00, and exactly four ledger entries.

## Design boundaries

The store is in-memory: process restart loses records, balances, pending work, and ledger entries. Synchronization is process-local and does not support multi-instance coordination. Global ordering is chosen for the fixture and can unnecessarily block unrelated accounts. Reversal transactions are rejected rather than being assigned invented semantics. These limitations and production alternatives are described in [design.md](design.md).
