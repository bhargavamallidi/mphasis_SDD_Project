package com.mphasis.txnprocessor.retry;

public record RetryPolicy(int maximumAttempts) {
    public RetryPolicy {
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("maximumAttempts must be at least one.");
        }
    }
}
