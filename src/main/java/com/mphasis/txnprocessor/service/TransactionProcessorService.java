package com.mphasis.txnprocessor.service;

import com.mphasis.txnprocessor.model.InputRecord;
import com.mphasis.txnprocessor.model.ProcessingOutcome;
import com.mphasis.txnprocessor.model.ProcessingSummary;
import com.mphasis.txnprocessor.model.TransactionRecord;
import com.mphasis.txnprocessor.model.TransactionRequest;
import com.mphasis.txnprocessor.model.TransactionStatus;
import com.mphasis.txnprocessor.ordering.SequenceOrderingService;
import com.mphasis.txnprocessor.retry.FailureSimulator;
import com.mphasis.txnprocessor.retry.RetryPolicy;
import com.mphasis.txnprocessor.store.InMemoryTransactionStore;
import com.mphasis.txnprocessor.validation.TransactionValidator;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class TransactionProcessorService {
    private final InMemoryTransactionStore store;
    private final TransactionValidator validator;
    private final SequenceOrderingService<PendingTransaction> ordering = new SequenceOrderingService<>();
    private final RetryPolicy retryPolicy;
    private final FailureSimulator failureSimulator;
    private final List<ProcessingOutcome> outcomes = new ArrayList<>();

    public TransactionProcessorService(
            InMemoryTransactionStore store,
            TransactionValidator validator,
            RetryPolicy retryPolicy,
            FailureSimulator failureSimulator) {
        this.store = store;
        this.validator = validator;
        this.retryPolicy = retryPolicy;
        this.failureSimulator = failureSimulator;
    }

    public synchronized List<ProcessingOutcome> processBatch(List<InputRecord> inputRecords) {
        for (InputRecord inputRecord : inputRecords) {
            process(inputRecord);
        }
        return List.copyOf(outcomes);
    }

    private void process(InputRecord inputRecord) {
        if (inputRecord.request() == null) {
            outcomes.add(new ProcessingOutcome(null, null, TransactionStatus.REJECTED, 0,
                    inputRecord.parseErrorCode()));
            System.out.printf("TRANSACTION_REJECTED errorCode=%s%n", inputRecord.parseErrorCode());
            return;
        }

        TransactionRequest request = inputRecord.request().normalized();
        System.out.printf("TRANSACTION_RECEIVED requestId=%s transactionId=%s sequence=%s%n",
                request.requestId(), request.transactionId(), request.sequenceNumber());
        int outcomeIndex = outcomes.size();
        TransactionRecord existing = store.findTransaction(request.transactionId());
        if (existing != null) {
            if (existing.request().sameBusinessPayload(request)) {
                outcomes.add(new ProcessingOutcome(request.transactionId(), request.sequenceNumber(),
                        TransactionStatus.DUPLICATE, 0, null));
                System.out.printf("TRANSACTION_DUPLICATE requestId=%s transactionId=%s%n",
                        request.requestId(), request.transactionId());
            } else {
                outcomes.add(new ProcessingOutcome(request.transactionId(), request.sequenceNumber(),
                        TransactionStatus.REJECTED, 0, "IDEMPOTENCY_CONFLICT"));
                System.out.printf("TRANSACTION_REJECTED transactionId=%s errorCode=IDEMPOTENCY_CONFLICT%n",
                        request.transactionId());
            }
            return;
        }

        boolean sequenceCanBeClaimed = validSequenceIdentity(request);
        if (sequenceCanBeClaimed
                && !store.claimSequence(request.sequenceNumber(), request.transactionId())) {
            outcomes.add(new ProcessingOutcome(request.transactionId(), request.sequenceNumber(),
                    TransactionStatus.REJECTED, 0, "SEQUENCE_CONFLICT"));
            System.out.printf("TRANSACTION_REJECTED transactionId=%s errorCode=SEQUENCE_CONFLICT%n",
                    request.transactionId());
            return;
        }

        Optional<String> validationError = validator.validate(request);
        if (validationError.isPresent()) {
            outcomes.add(new ProcessingOutcome(request.transactionId(), request.sequenceNumber(),
                    TransactionStatus.REJECTED, 0, validationError.get()));
            if (hasTransactionIdentity(request)) {
                store.register(request, TransactionStatus.REJECTED);
            }
            System.out.printf("TRANSACTION_REJECTED transactionId=%s errorCode=%s%n",
                    request.transactionId(), validationError.get());
            if (sequenceCanBeClaimed) {
                ordering.resolve(request.sequenceNumber());
                drainPending();
            }
            return;
        }

        outcomes.add(new ProcessingOutcome(request.transactionId(), request.sequenceNumber(),
                TransactionStatus.RECEIVED, 0, null));
        store.register(request, TransactionStatus.RECEIVED);
        long sequence = request.sequenceNumber();
        if (sequence < ordering.expectedSequence()) {
            store.update(request.transactionId(), TransactionStatus.REJECTED, 0, "INVALID_SEQUENCE");
            outcomes.set(outcomeIndex, outcome(request, TransactionStatus.REJECTED, 0, "INVALID_SEQUENCE"));
            System.out.printf("TRANSACTION_REJECTED transactionId=%s errorCode=INVALID_SEQUENCE%n",
                    request.transactionId());
        } else if (sequence > ordering.expectedSequence()) {
            store.update(request.transactionId(), TransactionStatus.PENDING, 0, null);
            outcomes.set(outcomeIndex, outcome(request, TransactionStatus.PENDING, 0, null));
            ordering.addPending(sequence, new PendingTransaction(request, outcomeIndex));
            System.out.printf("TRANSACTION_PENDING transactionId=%s sequence=%d%n",
                    request.transactionId(), sequence);
        } else {
            processExpected(new PendingTransaction(request, outcomeIndex));
            drainPending();
        }
    }

    private void processExpected(PendingTransaction pendingTransaction) {
        TransactionRequest request = pendingTransaction.request();
        int attempts = 0;
        for (int attempt = 1; attempt <= retryPolicy.maximumAttempts(); attempt++) {
            attempts = attempt;
            store.update(request.transactionId(), TransactionStatus.PROCESSING, attempts, null);
            if (!failureSimulator.shouldFail(request, attempt)) {
                String commitError = store.commit(request, attempts);
                if (commitError != null) {
                    store.update(request.transactionId(), TransactionStatus.REJECTED, attempts, commitError);
                    outcomes.set(pendingTransaction.outcomeIndex(),
                            outcome(request, TransactionStatus.REJECTED, attempts, commitError));
                    ordering.resolve(request.sequenceNumber());
                    System.out.printf("TRANSACTION_REJECTED transactionId=%s errorCode=%s%n",
                            request.transactionId(), commitError);
                    return;
                }
                outcomes.set(pendingTransaction.outcomeIndex(),
                        outcome(request, TransactionStatus.PROCESSED, attempts, null));
                ordering.resolve(request.sequenceNumber());
                System.out.printf("TRANSACTION_PROCESSED transactionId=%s sequence=%d attempts=%d%n",
                        request.transactionId(), request.sequenceNumber(), attempts);
                return;
            }

            if (attempt < retryPolicy.maximumAttempts()) {
                store.update(request.transactionId(), TransactionStatus.RETRY_PENDING, attempts, null);
                System.out.printf("RETRY_ATTEMPT transactionId=%s attempt=%d%n",
                        request.transactionId(), attempt + 1);
            }
        }

        store.update(request.transactionId(), TransactionStatus.FAILED, attempts, "RETRY_EXHAUSTED");
        outcomes.set(pendingTransaction.outcomeIndex(),
                outcome(request, TransactionStatus.FAILED, attempts, "RETRY_EXHAUSTED"));
        System.out.printf("TRANSACTION_FAILED transactionId=%s errorCode=RETRY_EXHAUSTED%n",
                request.transactionId());
    }

    private void drainPending() {
        PendingTransaction next;
        while ((next = ordering.removeExpectedPending()) != null) {
            processExpected(next);
            if (store.findTransaction(next.request().transactionId()).status() == TransactionStatus.FAILED) {
                return;
            }
        }
    }

    private ProcessingOutcome outcome(
            TransactionRequest request, TransactionStatus status, int attempts, String errorCode) {
        return new ProcessingOutcome(request.transactionId(), request.sequenceNumber(), status, attempts, errorCode);
    }

    private boolean validSequenceIdentity(TransactionRequest request) {
        return hasTransactionIdentity(request)
                && request.sequenceNumber() != null
                && request.sequenceNumber() > 0;
    }

    private boolean hasTransactionIdentity(TransactionRequest request) {
        return request.transactionId() != null && !request.transactionId().isBlank();
    }

    public synchronized ProcessingSummary summary(int requestsRead) {
        Map<TransactionStatus, Long> outcomeCounts = emptyCounts();
        outcomes.forEach(outcome -> outcomeCounts.merge(outcome.status(), 1L, Long::sum));
        Map<TransactionStatus, Long> canonicalCounts = emptyCounts();
        store.transactions().forEach(record -> canonicalCounts.merge(record.status(), 1L, Long::sum));
        int totalAttempts = store.transactions().stream().mapToInt(TransactionRecord::attemptCount).sum();
        List<Long> missing = ordering.missingSequences().stream()
                .filter(sequence -> store.sequenceOwner(sequence) == null)
                .toList();
        Long blockingSequence = store.transactions().stream()
                .filter(record -> record.status() == TransactionStatus.FAILED)
                .map(record -> record.request().sequenceNumber())
                .min(Long::compareTo)
                .orElse(null);
        return new ProcessingSummary(requestsRead, Map.copyOf(outcomeCounts), Map.copyOf(canonicalCounts),
                totalAttempts, ordering.pendingSequences(), missing, blockingSequence);
    }

    private Map<TransactionStatus, Long> emptyCounts() {
        Map<TransactionStatus, Long> counts = new EnumMap<>(TransactionStatus.class);
        for (TransactionStatus status : TransactionStatus.values()) {
            counts.put(status, 0L);
        }
        return counts;
    }

    public InMemoryTransactionStore store() {
        return store;
    }

    private record PendingTransaction(TransactionRequest request, int outcomeIndex) {
    }
}
