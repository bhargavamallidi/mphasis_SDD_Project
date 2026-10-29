package com.mphasis.txnprocessor.retry;

import com.mphasis.txnprocessor.model.TransactionRequest;

@FunctionalInterface
public interface FailureSimulator {
    boolean shouldFail(TransactionRequest request, int attemptNumber);
}
