package com.mphasis.txnprocessor.validation;

import com.mphasis.txnprocessor.model.TransactionRequest;

import java.util.Optional;
import java.util.regex.Pattern;

public final class TransactionValidator {
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public Optional<String> validate(TransactionRequest rawRequest) {
        if (rawRequest == null) {
            return Optional.of("INVALID_JSON_RECORD");
        }
        TransactionRequest request = rawRequest.normalized();
        if (blank(request.transactionId()) || blank(request.requestId()) || blank(request.accountId())
                || blank(request.transactionType()) || blank(request.currency())
                || request.amount() == null || request.sequenceNumber() == null) {
            return Optional.of("MISSING_REQUIRED_FIELD");
        }
        if (request.amount().signum() <= 0) {
            return Optional.of("INVALID_AMOUNT");
        }
        if (!CURRENCY.matcher(request.currency()).matches()) {
            return Optional.of("INVALID_CURRENCY");
        }
        if (request.sequenceNumber() <= 0) {
            return Optional.of("INVALID_SEQUENCE");
        }
        if (!request.transactionType().equals("CREDIT") && !request.transactionType().equals("DEBIT")) {
            return Optional.of("UNSUPPORTED_TRANSACTION_TYPE");
        }
        return Optional.empty();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
