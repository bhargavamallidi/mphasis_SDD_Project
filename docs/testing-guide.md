# Test guide

This guide explains the automated tests in `src/test/java/com/mphasis/txnprocessor/`. The suite uses JUnit 5 and Maven Surefire. Tests create a fresh in-memory store and processor for each scenario; they do not depend on execution order or on state from another test.

Run the suite from the project root:

```powershell
mvn clean test
```

The verified suite contains 12 tests: 8 processor tests, 3 validator tests, and 1 input-reader test. They exercise functional behavior and observable financial effects—balances and ledger entries—in addition to statuses and error codes.

## Input-reader test

### `JsonTransactionInputReaderTest.isolatesMalformedRecordsInArray`

Creates a temporary JSON file with two array items: one valid transaction and one whose sequence number is a string instead of an integer.

It verifies that:

- both array entries are returned, preserving one outcome per input row;
- the valid transaction becomes a usable request;
- the malformed row becomes an `InputRecord` with `INVALID_JSON_RECORD` rather than aborting the entire array.

This tests item-level shape/type isolation. A malformed JSON document that cannot be parsed at all is an input-file error and is reported as an `IOException`; it is not treated as an individual rejected transaction.

## Validator tests

### `TransactionValidatorTest.acceptsValidCreditAndNormalizesCurrency`

Passes a structurally valid credit with lowercase transaction type and currency. It expects no validation error. The validator normalizes type/currency before checking them, so `credit` and `usd` are accepted in the same way as `CREDIT` and `USD`.

### `TransactionValidatorTest.rejectsMissingFieldsAndNonPositiveAmount`

Uses two separate requests to verify:

- a missing required `requestId` yields `MISSING_REQUIRED_FIELD`;
- a zero amount yields `INVALID_AMOUNT`.

The test asserts error codes directly, making the validation result part of the observable contract.

### `TransactionValidatorTest.rejectsInvalidCurrencySequenceAndUnsupportedType`

Checks three independent validation failures:

- a currency containing a digit (`US1`) yields `INVALID_CURRENCY`;
- sequence number zero yields `INVALID_SEQUENCE`;
- `REVERSAL`, which is deliberately not implemented, yields `UNSUPPORTED_TRANSACTION_TYPE`.

## Processor and integration tests

The following tests use `TransactionProcessorServiceTest`. Its helpers construct requests with `BigDecimal`, a fresh store, and a deterministic failure simulator. The sample-batch test reads the real fixture at `input/transactions.json`.

### `processesSampleBatchWithCorrectBalancesAndExactlyFourLedgerEntries`

Runs the full assessment fixture with a configured one-time failure for `TXN-2005`.

It checks the acceptance behavior end to end:

- exactly four successful ledger entries;
- `ACC-201/USD` ends at 85.00, from credit 100, debit 40, and credit 25;
- `ACC-202/USD` remains zero because its negative-amount request is rejected;
- `ACC-203/USD` ends at 500 after retry;
- the first, initially out-of-order `TXN-2002` delivery ultimately has a `PROCESSED` outcome;
- the later delivery is `DUPLICATE` and reports zero attempts;
- `TXN-2004` is rejected with `INVALID_AMOUNT`;
- the final `TXN-2005` request outcome reports two attempts;
- total attempts across canonical transactions equal five.

Together these assertions catch duplicate financial effects, incorrect ordering, invalid-record side effects, and retry side effects.

### `reportsGapAndDrainsSequenceTwoAfterSequenceOneArrives`

Submits sequence 2 followed by sequence 1 in the same batch. It verifies both requests finish as `PROCESSED`, ledger order is sequence 1 then 2, the final balance is 30, and the gap list is empty after processing.

This proves input arrival order does not determine commit order and that resolving the missing sequence drains the pending contiguous transaction.

### `reportsMissingSequenceWhileHigherSequenceIsPending`

Submits only sequence 2 while the cursor expects sequence 1. It expects a `PENDING` request outcome, missing sequence list `[1]`, and pending sequence list `[2]`. No transaction can be committed until the gap is filled.

### `rejectsExactAndConflictingDuplicatesWithoutSecondEffect`

Submits three deliveries using the same transaction ID:

1. A credit of 10.0 USD.
2. The same business payload represented as 10.00 USD and lowercase `usd`, but with another `requestId`.
3. A changed amount of 11 USD.

The second delivery must be `DUPLICATE`: decimal scale, letter case, and delivery ID do not change business identity. The third must be rejected with `IDEMPOTENCY_CONFLICT`. The balance remains 10 and the ledger contains only one entry.

### `rejectsInsufficientFundsWithoutChangingLedgerOrBalance`

Attempts to debit one USD from an account with no prior funds. It asserts `INSUFFICIENT_FUNDS`, zero balance, and an empty ledger. This verifies the store checks the proposed resulting balance before committing any financial effect.

### `exhaustedRetryBlocksLaterSequenceWithoutAnyFinancialEffect`

Configures a two-attempt policy and a simulator that fails every attempt. Sequence 1 therefore ends as `FAILED` with `RETRY_EXHAUSTED`; sequence 2 remains `PENDING`; the summary reports blocking sequence 1; and there are no ledger entries or balance changes.

This covers retry exhaustion, failure isolation from financial effects, and strict sequence blocking. The sample integration test covers the complementary successful retry path.

### `rejectsSequenceCollisionAndKeepsCurrencyBalancesSeparate`

Processes a USD credit at sequence 1, then attempts a different transaction ID at the same sequence. That transaction is rejected with `SEQUENCE_CONFLICT`. A valid EUR credit at sequence 2 then succeeds. The test verifies USD remains 10, EUR becomes 5, and there are two ledger entries.

This jointly tests unique sequence ownership and separate `(accountId, currency)` balance keys.

### `invalidAmountResolvesSequenceAndDoesNotStopFollowingTransaction`

Submits a negative amount at sequence 1 followed by a valid credit at sequence 2. It expects the first to be rejected with `INVALID_AMOUNT`, the second to be processed, one ledger entry, and a final balance of 5.

This verifies the documented assumption that a definitively rejected record with a valid, unused sequence resolves that sequence slot, so an invalid record does not stall the stream.

## What these tests do not claim

- They do not prove persistence across process restarts; state is intentionally in memory.
- They do not prove coordination across multiple service instances or distributed exactly-once processing.
- They do not test reversal semantics because reversals are explicitly unsupported.
- The reader test covers a malformed array item, not an unreadable path or an entirely malformed JSON document.
- There is no separate `SequenceOrderingServiceTest` class; ordering behavior is tested through processor-level outcomes, balances, ledger order, gaps, and blocking behavior.

## Reading the reports

Captured evidence from the verified run is available under `docs/reports/`: the Maven console transcript, Surefire per-class text/XML reports, and the complete sample application output.
