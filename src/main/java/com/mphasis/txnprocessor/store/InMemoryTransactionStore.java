package com.mphasis.txnprocessor.store;

import com.mphasis.txnprocessor.model.LedgerEntry;
import com.mphasis.txnprocessor.model.TransactionRecord;
import com.mphasis.txnprocessor.model.TransactionRequest;
import com.mphasis.txnprocessor.model.TransactionStatus;
import com.mphasis.txnprocessor.model.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class InMemoryTransactionStore {
    private final Map<String, TransactionRecord> transactions = new HashMap<>();
    private final Map<Long, String> sequenceOwners = new HashMap<>();
    private final Map<AccountCurrency, BigDecimal> balances = new TreeMap<>();
    private final List<LedgerEntry> ledger = new ArrayList<>();

    public synchronized TransactionRecord findTransaction(String transactionId) {
        return transactions.get(transactionId);
    }

    public synchronized boolean claimSequence(long sequence, String transactionId) {
        String existing = sequenceOwners.putIfAbsent(sequence, transactionId);
        return existing == null || existing.equals(transactionId);
    }

    public synchronized String sequenceOwner(long sequence) {
        return sequenceOwners.get(sequence);
    }

    public synchronized TransactionRecord register(TransactionRequest request, TransactionStatus status) {
        TransactionRecord record = new TransactionRecord(request, status);
        transactions.put(request.transactionId(), record);
        if (request.accountId() != null && !request.accountId().isBlank()
                && request.currency() != null && request.currency().matches("[A-Z]{3}")) {
            balances.putIfAbsent(new AccountCurrency(request.accountId(), request.currency()), BigDecimal.ZERO);
        }
        return record;
    }

    public synchronized void update(
            String transactionId, TransactionStatus status, int attempts, String errorCode) {
        TransactionRecord record = transactions.get(transactionId);
        if (record != null) {
            record.update(status, attempts, errorCode);
        }
    }

    public synchronized String commit(TransactionRequest rawRequest, int attempts) {
        TransactionRequest request = rawRequest.normalized();
        AccountCurrency key = new AccountCurrency(request.accountId(), request.currency());
        BigDecimal current = balances.getOrDefault(key, BigDecimal.ZERO);
        TransactionType type = TransactionType.valueOf(request.transactionType());
        BigDecimal updated = type == TransactionType.CREDIT
                ? current.add(request.amount())
                : current.subtract(request.amount());
        if (updated.signum() < 0) {
            return "INSUFFICIENT_FUNDS";
        }

        ledger.add(new LedgerEntry(
                request.transactionId(), request.accountId(), type, request.amount(), request.currency(),
                request.sequenceNumber(), updated, Instant.now()));
        balances.put(key, updated);
        update(request.transactionId(), TransactionStatus.PROCESSED, attempts, null);
        return null;
    }

    public synchronized BigDecimal balance(String accountId, String currency) {
        return balances.getOrDefault(
                new AccountCurrency(accountId, currency), BigDecimal.ZERO);
    }

    public synchronized Map<String, BigDecimal> balances() {
        Map<String, BigDecimal> result = new TreeMap<>();
        balances.forEach((key, value) -> result.put(key.accountId + "/" + key.currency, value));
        return Collections.unmodifiableMap(result);
    }

    public synchronized List<LedgerEntry> ledger() {
        return List.copyOf(ledger);
    }

    public synchronized List<TransactionRecord> transactions() {
        return List.copyOf(transactions.values());
    }

    private record AccountCurrency(String accountId, String currency)
            implements Comparable<AccountCurrency> {
        @Override
        public int compareTo(AccountCurrency other) {
            int accountComparison = accountId.compareTo(other.accountId);
            return accountComparison != 0 ? accountComparison : currency.compareTo(other.currency);
        }
    }
}
