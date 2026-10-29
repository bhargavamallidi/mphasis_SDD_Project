package com.mphasis.txnprocessor.model;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

public record TransactionRequest(
        String transactionId,
        String requestId,
        Long sequenceNumber,
        String accountId,
        String transactionType,
        BigDecimal amount,
        String currency) {

    public TransactionRequest normalized() {
        return new TransactionRequest(
                trim(transactionId),
                trim(requestId),
                sequenceNumber,
                trim(accountId),
                transactionType == null ? null : transactionType.trim().toUpperCase(Locale.ROOT),
                amount,
                currency == null ? null : currency.trim().toUpperCase(Locale.ROOT));
    }

    public boolean sameBusinessPayload(TransactionRequest other) {
        TransactionRequest left = normalized();
        TransactionRequest right = other.normalized();
        return Objects.equals(left.transactionId, right.transactionId)
                && Objects.equals(left.accountId, right.accountId)
                && Objects.equals(left.transactionType, right.transactionType)
                && decimalEquals(left.amount, right.amount)
                && Objects.equals(left.currency, right.currency)
                && Objects.equals(left.sequenceNumber, right.sequenceNumber);
    }

    private static boolean decimalEquals(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
