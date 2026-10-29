# Design note

## Architecture

`JsonTransactionInputReader` maps each JSON array item to an input record and isolates invalid rows. It contains no transaction rules. `TransactionProcessorService` applies idempotency, validation, sequence handling, retries, and reporting outcomes. `InMemoryTransactionStore` owns canonical transactions, sequence ownership, account/currency balances, and ledger entries. `SequenceOrderingService` tracks the expected global sequence, pending requests, and resolved slots.

## Invariants

- `transactionId` identifies the business transaction; `requestId` identifies a delivery.
- Immutable business fields are compared after trimming and upper-casing type/currency. Decimal equality uses numeric value, not `BigDecimal` scale.
- A sequence can only be claimed by one transaction ID. Validation-rejected records with a usable, unclaimed sequence resolve the slot, but produce no financial effect.
- A request with a sequence above the cursor is pending. When a gap closes, contiguous pending records are drained in sequence order.
- A canonical transaction that exhausts retries becomes `FAILED`; its sequence remains unresolved and prevents later processing.
- The synchronized store commit checks the proposed balance first, then adds a ledger entry, updates balance, and records `PROCESSED`. Insufficient funds leaves the ledger and balance untouched.
- The processor and store are in-memory and single-process only.

## Statuses, retries, and reports

Canonical records move through `RECEIVED`, `PENDING` or `PROCESSING`, optional `RETRY_PENDING`, then a terminal state. `DUPLICATE` is a delivery outcome rather than the canonical state of a successfully processed transaction. The report therefore provides final per-request outcome counts and separate unique canonical-state counts.

The retry policy defaults to three total attempts, without delay. `ConfiguredFailureSimulator` supports deterministic transient failures; the CLI configures one failure for `TXN-2005`. No partial balance or ledger mutation occurs on a simulated failure. `RETRY_EXHAUSTED` is terminal in this prototype and requires manual recovery.

## Business assumptions and limitations

- Ordering is one global contiguous stream starting at 1 because that matches the sample. Production ordering should use the upstream contract, frequently an account or aggregate key.
- A valid unused sequence on a rejected transaction resolves that slot so an invalid record does not permanently stall the stream.
- Only CREDIT and DEBIT are implemented. Reversal requests are rejected as unsupported rather than being treated as an implicit credit/debit.
- Accounts cannot overdraw.
- The service does not persist data; restart loses transaction state, balances, pending sequence records, and ledger.
- In-process synchronization does not coordinate multiple application instances.

## Production hardening

Replace the in-memory store with a relational database and commit idempotency, status, balance, and ledger changes in one database transaction. Enforce unique transaction IDs and sequence ownership in the database; use row locking or optimistic concurrency. Add a durable queue adapter and acknowledge only after durable processing. Persist retry attempts and schedules, use exponential backoff with jitter, and route exhausted work to a dead-letter/manual-review flow. Establish an upstream ordering key and operational gap-recovery process. Add structured logs, metrics, dashboards, alerts, least-privilege access, externalized secrets, input limits, replay/reconciliation tools, and integration plus concurrency tests.
