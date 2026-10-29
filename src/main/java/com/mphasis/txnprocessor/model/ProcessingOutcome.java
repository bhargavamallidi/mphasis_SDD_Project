package com.mphasis.txnprocessor.model;

public record ProcessingOutcome(
        String transactionId,
        Long sequenceNumber,
        TransactionStatus status,
        int attemptCount,
        String errorCode) {
}
