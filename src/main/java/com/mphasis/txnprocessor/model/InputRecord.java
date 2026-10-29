package com.mphasis.txnprocessor.model;

public record InputRecord(TransactionRequest request, String parseErrorCode) {
    public static InputRecord valid(TransactionRequest request) {
        return new InputRecord(request, null);
    }

    public static InputRecord invalid(String errorCode) {
        return new InputRecord(null, errorCode);
    }
}
