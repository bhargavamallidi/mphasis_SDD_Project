package com.mphasis.txnprocessor.ordering;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

public final class SequenceOrderingService<T> {
    private long expectedSequence = 1;
    private final TreeMap<Long, T> pending = new TreeMap<>();
    private final TreeSet<Long> resolved = new TreeSet<>();

    public long expectedSequence() {
        return expectedSequence;
    }

    public void addPending(long sequence, T item) {
        pending.put(sequence, item);
    }

    public T removeExpectedPending() {
        return pending.remove(expectedSequence);
    }

    public boolean hasPending(long sequence) {
        return pending.containsKey(sequence);
    }

    public List<Long> pendingSequences() {
        return List.copyOf(pending.keySet());
    }

    public void resolve(long sequence) {
        resolved.add(sequence);
        advanceResolved();
    }

    private void advanceResolved() {
        while (resolved.remove(expectedSequence)) {
            expectedSequence++;
        }
    }

    public List<Long> missingSequences() {
        if (pending.isEmpty() || pending.firstKey() <= expectedSequence) {
            return List.of();
        }
        List<Long> gaps = new ArrayList<>();
        for (long sequence = expectedSequence; sequence < pending.firstKey(); sequence++) {
            if (!resolved.contains(sequence)) {
                gaps.add(sequence);
            }
        }
        return List.copyOf(gaps);
    }
}
