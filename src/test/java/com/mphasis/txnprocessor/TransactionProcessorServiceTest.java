package com.mphasis.txnprocessor;

import com.mphasis.txnprocessor.model.InputRecord;
import com.mphasis.txnprocessor.model.ProcessingOutcome;
import com.mphasis.txnprocessor.model.TransactionRequest;
import com.mphasis.txnprocessor.model.TransactionStatus;
import com.mphasis.txnprocessor.retry.ConfiguredFailureSimulator;
import com.mphasis.txnprocessor.retry.RetryPolicy;
import com.mphasis.txnprocessor.service.TransactionProcessorService;
import com.mphasis.txnprocessor.store.InMemoryTransactionStore;
import com.mphasis.txnprocessor.validation.TransactionValidator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransactionProcessorServiceTest {
    @Test
    void processesSampleBatchWithCorrectBalancesAndExactlyFourLedgerEntries() throws Exception {
        InMemoryTransactionStore store = new InMemoryTransactionStore();
        TransactionProcessorService processor = processor(store,
                new ConfiguredFailureSimulator(Map.of("TXN-2005", 1)));
        List<ProcessingOutcome> outcomes = processor.processBatch(new com.mphasis.txnprocessor.input
                .JsonTransactionInputReader().read(java.nio.file.Path.of("input", "transactions.json")));

        assertEquals(4, store.ledger().size());
        assertMoney("85.00", store.balance("ACC-201", "USD"));
        assertMoney("0", store.balance("ACC-202", "USD"));
        assertMoney("500", store.balance("ACC-203", "USD"));
        assertEquals(TransactionStatus.PROCESSED, outcomes.get(0).status());
        assertEquals(TransactionStatus.DUPLICATE, outcomes.get(2).status());
        assertEquals(0, outcomes.get(2).attemptCount());
        assertEquals(TransactionStatus.REJECTED, outcomes.get(4).status());
        assertEquals("INVALID_AMOUNT", outcomes.get(4).errorCode());
        assertEquals(2, outcomes.get(5).attemptCount());
        assertEquals(5, processor.summary(6).totalAttempts());
    }

    @Test
    void reportsGapAndDrainsSequenceTwoAfterSequenceOneArrives() {
        InMemoryTransactionStore store = new InMemoryTransactionStore();
        TransactionProcessorService processor = processor(store, (request, attempt) -> false);
        List<ProcessingOutcome> outcomes = processor.processBatch(List.of(
                input(request("T2", "R2", 2, "A", "CREDIT", "20", "USD")),
                input(request("T1", "R1", 1, "A", "CREDIT", "10", "USD"))));

        assertEquals(TransactionStatus.PROCESSED, outcomes.get(0).status());
        assertEquals(TransactionStatus.PROCESSED, outcomes.get(1).status());
        assertEquals(List.of("T1", "T2"), store.ledger().stream().map(entry -> entry.transactionId()).toList());
        assertMoney("30", store.balance("A", "USD"));
        assertEquals(List.of(), processor.summary(2).missingSequences());
    }

    @Test
    void reportsMissingSequenceWhileHigherSequenceIsPending() {
        TransactionProcessorService processor = processor(new InMemoryTransactionStore(),
                (request, attempt) -> false);
        List<ProcessingOutcome> outcomes = processor.processBatch(List.of(
                input(request("T2", "R2", 2, "A", "CREDIT", "20", "USD"))));

        assertEquals(TransactionStatus.PENDING, outcomes.get(0).status());
        assertEquals(List.of(1L), processor.summary(1).missingSequences());
        assertEquals(List.of(2L), processor.summary(1).pendingSequences());
    }

    @Test
    void rejectsExactAndConflictingDuplicatesWithoutSecondEffect() {
        InMemoryTransactionStore store = new InMemoryTransactionStore();
        TransactionProcessorService processor = processor(store, (request, attempt) -> false);
        List<ProcessingOutcome> outcomes = processor.processBatch(List.of(
                input(request("T1", "R1", 1, "A", "CREDIT", "10.0", "usd")),
                input(request("T1", "R2", 1, "A", "CREDIT", "10.00", "USD")),
                input(request("T1", "R3", 1, "A", "CREDIT", "11", "USD"))));

        assertEquals(TransactionStatus.DUPLICATE, outcomes.get(1).status());
        assertEquals(TransactionStatus.REJECTED, outcomes.get(2).status());
        assertEquals("IDEMPOTENCY_CONFLICT", outcomes.get(2).errorCode());
        assertMoney("10.0", store.balance("A", "USD"));
        assertEquals(1, store.ledger().size());
    }

    @Test
    void rejectsInsufficientFundsWithoutChangingLedgerOrBalance() {
        InMemoryTransactionStore store = new InMemoryTransactionStore();
        TransactionProcessorService processor = processor(store, (request, attempt) -> false);
        List<ProcessingOutcome> outcomes = processor.processBatch(List.of(
                input(request("T1", "R1", 1, "A", "DEBIT", "1", "USD"))));

        assertEquals("INSUFFICIENT_FUNDS", outcomes.get(0).errorCode());
        assertMoney("0", store.balance("A", "USD"));
        assertEquals(0, store.ledger().size());
    }

    @Test
    void exhaustedRetryBlocksLaterSequenceWithoutAnyFinancialEffect() {
        InMemoryTransactionStore store = new InMemoryTransactionStore();
        TransactionProcessorService processor = new TransactionProcessorService(
                store, new TransactionValidator(), new RetryPolicy(2), (request, attempt) -> true);
        List<ProcessingOutcome> outcomes = processor.processBatch(List.of(
                input(request("T1", "R1", 1, "A", "CREDIT", "10", "USD")),
                input(request("T2", "R2", 2, "B", "CREDIT", "20", "USD"))));

        assertEquals(TransactionStatus.FAILED, outcomes.get(0).status());
        assertEquals("RETRY_EXHAUSTED", outcomes.get(0).errorCode());
        assertEquals(TransactionStatus.PENDING, outcomes.get(1).status());
        assertEquals(1L, processor.summary(2).blockingSequence());
        assertEquals(0, store.ledger().size());
        assertMoney("0", store.balance("A", "USD"));
    }

    @Test
    void rejectsSequenceCollisionAndKeepsCurrencyBalancesSeparate() {
        InMemoryTransactionStore store = new InMemoryTransactionStore();
        TransactionProcessorService processor = processor(store, (request, attempt) -> false);
        List<ProcessingOutcome> outcomes = processor.processBatch(List.of(
                input(request("T1", "R1", 1, "A", "CREDIT", "10", "USD")),
                input(request("T2", "R2", 1, "A", "CREDIT", "5", "EUR")),
                input(request("T3", "R3", 2, "A", "CREDIT", "5", "EUR"))));

        assertEquals("SEQUENCE_CONFLICT", outcomes.get(1).errorCode());
        assertMoney("10", store.balance("A", "USD"));
        assertMoney("5", store.balance("A", "EUR"));
        assertEquals(2, store.ledger().size());
    }

    @Test
    void invalidAmountResolvesSequenceAndDoesNotStopFollowingTransaction() {
        InMemoryTransactionStore store = new InMemoryTransactionStore();
        TransactionProcessorService processor = processor(store, (request, attempt) -> false);
        List<ProcessingOutcome> outcomes = processor.processBatch(List.of(
                input(request("T1", "R1", 1, "A", "CREDIT", "-1", "USD")),
                input(request("T2", "R2", 2, "A", "CREDIT", "5", "USD"))));

        assertEquals("INVALID_AMOUNT", outcomes.get(0).errorCode());
        assertEquals(TransactionStatus.PROCESSED, outcomes.get(1).status());
        assertEquals(1, store.ledger().size());
        assertMoney("5", store.balance("A", "USD"));
    }

    private TransactionProcessorService processor(
            InMemoryTransactionStore store, com.mphasis.txnprocessor.retry.FailureSimulator simulator) {
        return new TransactionProcessorService(store, new TransactionValidator(), new RetryPolicy(3), simulator);
    }

    private InputRecord input(TransactionRequest request) {
        return InputRecord.valid(request);
    }

    private TransactionRequest request(
            String id, String requestId, long sequence, String account, String type, String amount, String currency) {
        return new TransactionRequest(id, requestId, sequence, account, type, new BigDecimal(amount), currency);
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
