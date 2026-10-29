package com.mphasis.txnprocessor.model;

import java.math.BigDecimal;
import java.time.Instant;

public record LedgerEntry(
        String transactionId,
        String accountId,
        TransactionType transactionType,
        BigDecimal amount,
        String currency,
        long sequenceNumber,
        BigDecimal resultingBalance,
        Instant createdAt) {
}
