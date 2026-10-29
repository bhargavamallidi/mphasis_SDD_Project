package com.mphasis.txnprocessor;

import com.mphasis.txnprocessor.input.JsonTransactionInputReader;
import com.mphasis.txnprocessor.model.InputRecord;
import com.mphasis.txnprocessor.retry.ConfiguredFailureSimulator;
import com.mphasis.txnprocessor.retry.RetryPolicy;
import com.mphasis.txnprocessor.service.ProcessingReportService;
import com.mphasis.txnprocessor.service.TransactionProcessorService;
import com.mphasis.txnprocessor.store.InMemoryTransactionStore;
import com.mphasis.txnprocessor.validation.TransactionValidator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Path inputPath = args.length == 0 ? Path.of("input", "transactions.json") : Path.of(args[0]);
        List<InputRecord> requests = new JsonTransactionInputReader().read(inputPath);
        InMemoryTransactionStore store = new InMemoryTransactionStore();
        TransactionProcessorService processor = new TransactionProcessorService(
                store, new TransactionValidator(), new RetryPolicy(3),
                new ConfiguredFailureSimulator(Map.of("TXN-2005", 1)));
        var outcomes = processor.processBatch(requests);
        new ProcessingReportService().print(requests.size(), outcomes, processor.summary(requests.size()), store);
    }
}
