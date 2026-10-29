package com.mphasis.txnprocessor.service;

import com.mphasis.txnprocessor.model.LedgerEntry;
import com.mphasis.txnprocessor.model.ProcessingOutcome;
import com.mphasis.txnprocessor.model.ProcessingSummary;
import com.mphasis.txnprocessor.store.InMemoryTransactionStore;
import com.mphasis.txnprocessor.model.TransactionStatus;

import java.math.BigDecimal;
import java.util.StringJoiner;

public final class ProcessingReportService {
    public void print(
            int requestsRead,
            java.util.List<ProcessingOutcome> outcomes,
            ProcessingSummary summary,
            InMemoryTransactionStore store) {
        System.out.printf("Requests read: %d%n", requestsRead);
        System.out.println("Request outcomes:");
        for (ProcessingOutcome outcome : outcomes) {
            System.out.printf("  transactionId=%s sequence=%s status=%s attempts=%d%s%n",
                    outcome.transactionId(), outcome.sequenceNumber(), outcome.status(), outcome.attemptCount(),
                    outcome.errorCode() == null ? "" : " errorCode=" + outcome.errorCode());
        }
        System.out.printf("Request outcome counts: %s%n", formatCounts(summary.requestOutcomeCounts()));
        System.out.printf("Canonical transaction states: %s%n", formatCounts(summary.canonicalStateCounts()));
        System.out.printf("Total attempts: %d%n", summary.totalAttempts());
        System.out.printf("Pending sequences: %s%n", summary.pendingSequences());
        System.out.printf("Missing sequences: %s%n", summary.missingSequences());
        System.out.printf("Blocking sequence: %s%n", summary.blockingSequence());
        System.out.println("Final balances:");
        store.balances().forEach((accountCurrency, balance) ->
                System.out.printf("  %s=%s%n", accountCurrency, formatMoney(balance)));
        System.out.printf("Ledger entries: %d%n", store.ledger().size());
        for (LedgerEntry entry : store.ledger()) {
            System.out.printf("  %s %s %s %s -> %s%n", entry.transactionId(), entry.accountId(),
                    entry.transactionType(), formatMoney(entry.amount()), formatMoney(entry.resultingBalance()));
        }
    }

    private String formatMoney(BigDecimal amount) {
        return amount.setScale(Math.max(2, amount.scale())).toPlainString();
    }

    private String formatCounts(java.util.Map<TransactionStatus, Long> counts) {
        StringJoiner result = new StringJoiner(", ", "{", "}");
        for (TransactionStatus status : TransactionStatus.values()) {
            result.add(status + "=" + counts.getOrDefault(status, 0L));
        }
        return result.toString();
    }
}
