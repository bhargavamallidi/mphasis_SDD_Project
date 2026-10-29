package com.mphasis.txnprocessor.retry;

import com.mphasis.txnprocessor.model.TransactionRequest;

import java.util.Map;

public final class ConfiguredFailureSimulator implements FailureSimulator {
    private final Map<String, Integer> failuresBeforeSuccess;

    public ConfiguredFailureSimulator(Map<String, Integer> failuresBeforeSuccess) {
        this.failuresBeforeSuccess = Map.copyOf(failuresBeforeSuccess);
    }

    @Override
    public boolean shouldFail(TransactionRequest request, int attemptNumber) {
        return attemptNumber <= failuresBeforeSuccess.getOrDefault(request.transactionId(), 0);
    }
}
