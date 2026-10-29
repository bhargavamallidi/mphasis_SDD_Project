package com.mphasis.txnprocessor.input;

import com.mphasis.txnprocessor.model.InputRecord;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface TransactionInputReader {
    List<InputRecord> read(Path path) throws IOException;
}
