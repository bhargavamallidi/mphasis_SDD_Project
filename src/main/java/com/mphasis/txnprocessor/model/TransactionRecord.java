package com.mphasis.txnprocessor.model;

public final class TransactionRecord {
    private final TransactionRequest request;
    private TransactionStatus status;
    private int attemptCount;
    private String errorCode;

    public TransactionRecord(TransactionRequest request, TransactionStatus status) {
        this.request = request;
        this.status = status;
    }

    public TransactionRequest request() {
        return request;
    }

    public TransactionStatus status() {
        return status;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public String errorCode() {
        return errorCode;
    }

    public void update(TransactionStatus newStatus, int attempts, String newErrorCode) {
        status = newStatus;
        attemptCount = attempts;
        errorCode = newErrorCode;
    }
}
