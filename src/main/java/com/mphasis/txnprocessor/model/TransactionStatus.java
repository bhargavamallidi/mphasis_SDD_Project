package com.mphasis.txnprocessor.model;

public enum TransactionStatus {
    RECEIVED,
    PROCESSING,
    PROCESSED,
    DUPLICATE,
    PENDING,
    RETRY_PENDING,
    REJECTED,
    FAILED
}
