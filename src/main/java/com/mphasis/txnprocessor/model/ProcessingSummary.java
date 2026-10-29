package com.mphasis.txnprocessor.model;

import java.util.List;
import java.util.Map;

public record ProcessingSummary(
        int requestsRead,
        Map<TransactionStatus, Long> requestOutcomeCounts,
        Map<TransactionStatus, Long> canonicalStateCounts,
        int totalAttempts,
        List<Long> pendingSequences,
        List<Long> missingSequences,
        Long blockingSequence) {
}
